package xyz.xenondevs.invui.internal.util;

import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.recipe.RecipeDisplayEntry;
import com.github.retrooper.packetevents.protocol.recipe.display.FurnaceRecipeDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.RecipeDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.ShapedCraftingRecipeDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.ShapelessCraftingRecipeDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.SmithingRecipeDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.StonecutterRecipeDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.AnyFuelSlotDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.CompositeSlotDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.EmptySlotDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.ItemSlotDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.ItemStackSlotDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.SlotDisplay;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Bridges the 1.21.2+ id-based recipe protocol and Bukkit's key-based recipe API.
 * <p>
 * Since 1.21.2, clients never learn recipe keys — the server sends numeric display ids and
 * per-id display data, and recipe-book clicks reference those ids. The server-side id
 * assignment lives in NMS only, so this class recovers the key by fingerprinting the
 * display data that was sent to the client (cached by the packet listener) against the
 * recipes exposed through {@link Bukkit#recipeIterator()}.
 */
public final class RecipeResolver {

    /** Result item + ingredient fingerprint of a recipe display sent to a client. */
    public record CachedRecipe(
        @Nullable ResourceLocation resultItem,
        int resultAmount,
        @Nullable List<@Nullable List<ResourceLocation>> ingredients
    ) {}

    private record BukkitCandidate(
        NamespacedKey key,
        int resultAmount,
        @Nullable List<@Nullable Set<String>> ingredients
    ) {}

    private static volatile @Nullable Map<String, List<BukkitCandidate>> index;

    private RecipeResolver() {}

    public static void invalidateIndex() {
        index = null;
    }

    /**
     * Extracts a matching fingerprint from a recipe display entry as sent to a client.
     */
    public static CachedRecipe fromDisplayEntry(RecipeDisplayEntry entry) {
        SlotDisplay<?> result = switch (entry.getDisplay()) {
            case ShapedCraftingRecipeDisplay d -> d.getResult();
            case ShapelessCraftingRecipeDisplay d -> d.getResult();
            case FurnaceRecipeDisplay d -> d.getResult();
            case SmithingRecipeDisplay d -> d.getResult();
            case StonecutterRecipeDisplay d -> d.getResult();
            default -> null;
        };

        ResourceLocation resultItem = null;
        int resultAmount = 0;
        if (result instanceof ItemStackSlotDisplay stackDisplay) {
            resultItem = stackDisplay.getStack().getType().getName();
            resultAmount = stackDisplay.getStack().getAmount();
        } else if (result instanceof ItemSlotDisplay itemDisplay) {
            resultItem = itemDisplay.getItem().getName();
            resultAmount = 1;
        }

        List<@Nullable List<ResourceLocation>> ingredients = null;
        var sets = entry.getIngredients();
        if (sets != null) {
            ingredients = new ArrayList<>(sets.size());
            for (var set : sets) {
                var entities = set.getEntities();
                // tag-backed sets cannot be expanded without registry access
                ingredients.add(entities != null ? entities.stream().map(ItemType::getName).toList() : null);
            }
        }

        return new CachedRecipe(resultItem, resultAmount, ingredients);
    }

    /**
     * Resolves the recipe key most likely referenced by the given cached display data.
     * Matching is by result item, then result amount, then ingredient sets; if multiple
     * recipes remain indistinguishable, the first match is returned as a best effort.
     */
    public static @Nullable Key resolveKey(CachedRecipe cached) {
        var resultItem = cached.resultItem();
        if (resultItem == null)
            return null;

        var candidates = getIndex().get(resultItem.toString());
        if (candidates == null || candidates.isEmpty())
            return null;

        var matches = candidates.stream()
            .filter(candidate -> candidate.resultAmount() == cached.resultAmount())
            .toList();
        if (matches.isEmpty())
            matches = candidates;
        if (matches.size() > 1 && cached.ingredients() != null) {
            for (var candidate : matches) {
                if (ingredientsMatch(cached.ingredients(), candidate.ingredients()))
                    return toKey(candidate.key());
            }
        }

        return toKey(matches.getFirst().key());
    }

    private static Key toKey(NamespacedKey key) {
        return Key.key(key.getNamespace(), key.getKey());
    }

    /**
     * Compares ingredient lists as multisets of item-id sets. Entries that could not be
     * expanded on either side (tags, exotic choices) make the comparison fail, falling
     * back to the first-candidate heuristic in {@link #resolveKey}.
     */
    private static boolean ingredientsMatch(
        List<@Nullable List<ResourceLocation>> cached,
        @Nullable List<@Nullable Set<String>> bukkit
    ) {
        if (bukkit == null || cached.size() != bukkit.size())
            return false;

        var cachedCounts = new TreeMap<String, Integer>();
        for (var set : cached) {
            if (set == null)
                return false;
            var canonical = set.stream().map(ResourceLocation::toString).sorted().collect(Collectors.joining(","));
            cachedCounts.merge(canonical, 1, Integer::sum);
        }

        var bukkitCounts = new TreeMap<String, Integer>();
        for (var set : bukkit) {
            if (set == null)
                return false;
            var canonical = set.stream().sorted().collect(Collectors.joining(","));
            bukkitCounts.merge(canonical, 1, Integer::sum);
        }

        return cachedCounts.equals(bukkitCounts);
    }

    private static Map<String, List<BukkitCandidate>> getIndex() {
        var idx = index;
        if (idx == null) {
            synchronized (RecipeResolver.class) {
                idx = index;
                if (idx == null) {
                    idx = buildIndex();
                    index = idx;
                }
            }
        }
        return idx;
    }

    private static Map<String, List<BukkitCandidate>> buildIndex() {
        var idx = new HashMap<String, List<BukkitCandidate>>();
        var iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            if (!(recipe instanceof Keyed keyed))
                continue;
            var result = recipe.getResult();
            if (result.getType().isAir())
                continue;
            idx.computeIfAbsent(result.getType().getKey().toString(), resultKey -> new ArrayList<>())
                .add(new BukkitCandidate(keyed.getKey(), result.getAmount(), extractChoices(recipe)));
        }
        return idx;
    }

    private static @Nullable List<@Nullable Set<String>> extractChoices(Recipe recipe) {
        List<@Nullable RecipeChoice> choices = switch (recipe) {
            case ShapedRecipe shaped -> {
                var byChar = shaped.getChoiceMap();
                var flat = new ArrayList<@Nullable RecipeChoice>();
                for (String row : shaped.getShape()) {
                    for (char c : row.toCharArray()) {
                        flat.add(byChar.get(c));
                    }
                }
                yield flat;
            }
            case ShapelessRecipe shapeless -> new ArrayList<@Nullable RecipeChoice>(shapeless.getChoiceList());
            case CookingRecipe<?> cooking -> List.of(cooking.getInputChoice());
            default -> null;
        };
        if (choices == null)
            return null;

        return choices.stream()
            .filter(Objects::nonNull)
            .map(RecipeResolver::choiceToIdSet)
            .toList();
    }

    private static @Nullable Set<String> choiceToIdSet(RecipeChoice choice) {
        return switch (choice) {
            case RecipeChoice.MaterialChoice materials -> materials.getChoices().stream()
                .map(material -> material.getKey().toString())
                .collect(Collectors.toSet());
            case RecipeChoice.ExactChoice exact -> exact.getChoices().stream()
                .map(item -> item.getType().getKey().toString())
                .collect(Collectors.toSet());
            default -> null;
        };
    }

    /**
     * Builds a client-side recipe display for a Bukkit recipe, used to show ghost recipes
     * on 1.21.2+ where the place-ghost-recipe packet carries display data instead of a key.
     * Returns null for recipe types that have no menu-displayable form.
     */
    public static @Nullable RecipeDisplay<?> toDisplay(Recipe recipe) {
        return switch (recipe) {
            case ShapedRecipe shaped -> toShapedDisplay(shaped);
            case ShapelessRecipe shapeless -> new ShapelessCraftingRecipeDisplay(
                shapeless.getChoiceList().stream().map(RecipeResolver::toSlotDisplay).toList(),
                resultDisplay(shapeless),
                stationDisplay(ItemTypes.CRAFTING_TABLE)
            );
            case BlastingRecipe blasting -> toCookingDisplay(blasting, ItemTypes.BLAST_FURNACE);
            case SmokingRecipe smoking -> toCookingDisplay(smoking, ItemTypes.SMOKER);
            case CampfireRecipe campfire -> toCookingDisplay(campfire, ItemTypes.CAMPFIRE);
            case FurnaceRecipe furnace -> toCookingDisplay(furnace, ItemTypes.FURNACE);
            default -> null;
        };
    }

    private static RecipeDisplay<?> toShapedDisplay(ShapedRecipe shaped) {
        String[] shape = shaped.getShape();
        int height = shape.length;
        int width = height > 0 ? shape[0].length() : 0;
        var choiceMap = shaped.getChoiceMap();

        var ingredients = new ArrayList<SlotDisplay<?>>(width * height);
        for (String row : shape) {
            for (int x = 0; x < width; x++) {
                RecipeChoice choice = x < row.length() ? choiceMap.get(row.charAt(x)) : null;
                ingredients.add(toSlotDisplay(choice));
            }
        }

        return new ShapedCraftingRecipeDisplay(
            width, height, ingredients,
            resultDisplay(shaped),
            stationDisplay(ItemTypes.CRAFTING_TABLE)
        );
    }

    private static RecipeDisplay<?> toCookingDisplay(CookingRecipe<?> recipe, ItemType station) {
        return new FurnaceRecipeDisplay(
            toSlotDisplay(recipe.getInputChoice()),
            AnyFuelSlotDisplay.INSTANCE,
            resultDisplay(recipe),
            stationDisplay(station),
            recipe.getCookingTime(),
            recipe.getExperience()
        );
    }

    private static SlotDisplay<?> resultDisplay(Recipe recipe) {
        return new ItemStackSlotDisplay(SpigotConversionUtil.fromBukkitItemStack(recipe.getResult()));
    }

    private static SlotDisplay<?> stationDisplay(ItemType station) {
        return new ItemSlotDisplay(station);
    }

    private static SlotDisplay<?> toSlotDisplay(@Nullable RecipeChoice choice) {
        return switch (choice) {
            case null -> EmptySlotDisplay.INSTANCE;
            case RecipeChoice.MaterialChoice materials -> {
                var displays = materials.getChoices().stream()
                    .map(material -> ItemTypes.getByName(material.getKey().toString()))
                    .filter(Objects::nonNull)
                    .<SlotDisplay<?>>map(ItemSlotDisplay::new)
                    .toList();
                yield compact(displays);
            }
            case RecipeChoice.ExactChoice exact -> {
                var displays = exact.getChoices().stream()
                    .<SlotDisplay<?>>map(item -> new ItemStackSlotDisplay(SpigotConversionUtil.fromBukkitItemStack(item)))
                    .toList();
                yield compact(displays);
            }
            default -> EmptySlotDisplay.INSTANCE;
        };
    }

    private static SlotDisplay<?> compact(List<SlotDisplay<?>> displays) {
        return switch (displays.size()) {
            case 0 -> EmptySlotDisplay.INSTANCE;
            case 1 -> displays.getFirst();
            default -> new CompositeSlotDisplay(displays);
        };
    }
}
