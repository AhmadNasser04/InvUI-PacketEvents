package xyz.xenondevs.invui.internal.util;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.EntityBlockStorage;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import xyz.xenondevs.invui.InvUI;
import xyz.xenondevs.invui.util.ItemUtils;

import java.util.ArrayList;
import java.util.List;

import static io.papermc.paper.datacomponent.item.BundleContents.bundleContents;

@SuppressWarnings("UnstableApiUsage")
public final class ItemUtils2 {

    /**
     * Stores a bundle's selected slot. The vanilla {@code selectedItem} field of the
     * {@code minecraft:bundle_contents} component is not exposed by the Paper or PacketEvents
     * APIs, so the selection is kept in the item's persistent data container instead.
     */
    private static final NamespacedKey BUNDLE_SELECTED_SLOT_KEY =
        new NamespacedKey(InvUI.getInstance().getPlugin(), "bundle_selected_slot");

    private ItemUtils2() {}
    
    /**
     * Creates a new list where all intermediary empty item stacks are replaced with the non-empty placeholder item
     * and all trailing empty item stacks are removed.
     *
     * @param items the list of item stacks
     * @return the sanitized list of item stacks
     */
    public static List<ItemStack> withoutIntermediaryEmpties(List<? extends @Nullable ItemStack> items) {
        var sanitized = new ArrayList<ItemStack>();
        
        int lastNonNull = -1;
        for (int i = 0; i < items.size(); i++) {
            var itemStack = items.get(i);
            if (itemStack != null) {
                lastNonNull = i;
            }
        }
        
        for (int i = 0; i <= lastNonNull; i++) {
            var itemStack = items.get(i);
            if (ItemUtils.isEmpty(itemStack)) {
                sanitized.add(ItemUtils.getPlaceholder());
            } else {
                sanitized.add(itemStack);
            }
        }
        
        return sanitized;
    }
    
    /**
     * Checks whether the given item stack is a bundle.
     *
     * @param bundle the item stack to check
     * @return true if the item stack is a bundle, false otherwise
     */
    public static boolean isBundle(ItemStack bundle) {
        return bundle.hasData(DataComponentTypes.BUNDLE_CONTENTS);
    }
    
    
    /**
     * Adds the given target stack to the given bundle stack, updating both
     * stacks appropriately, applying the vanilla bundle weight rules.
     *
     * @param bundle the bundle item stack
     * @param target the target item stack
     * @return true if anything was added to the bundle, false otherwise
     */
    public static boolean tryMoveIntoBundle(ItemStack bundle, ItemStack target) {
        if (ItemUtils.isEmpty(target))
            return false;

        var contents = bundle.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (contents == null)
            return false;

        int maxToAdd = getMaxAmountToAddToBundle(bundle, target);
        if (maxToAdd <= 0)
            return false;

        var newList = new ArrayList<>(contents.contents());
        int remaining = Math.min(target.getAmount(), maxToAdd);
        int added = 0;

        // try to merge with an existing matching stack first
        for (int i = 0; i < newList.size() && remaining > 0; i++) {
            var existing = newList.get(i);
            if (existing.isSimilar(target)) {
                int headroom = existing.getMaxStackSize() - existing.getAmount();
                if (headroom > 0) {
                    int merge = Math.min(remaining, headroom);
                    var merged = existing.clone();
                    merged.setAmount(existing.getAmount() + merge);
                    newList.set(i, merged);
                    remaining -= merge;
                    added += merge;
                }
            }
        }

        // spill the rest into a new stack
        if (remaining > 0) {
            var fresh = target.clone();
            fresh.setAmount(remaining);
            newList.add(fresh);
            added += remaining;
        }

        if (added == 0)
            return false;

        bundle.setData(DataComponentTypes.BUNDLE_CONTENTS, bundleContents(newList));
        target.setAmount(target.getAmount() - added);
        return true;
    }
    
    /**
     * Calculates the difference in item amounts between two bundles, matching for a given target item stack.
     *
     * @param bundleA the first bundle item stack
     * @param bundleB the second bundle item stack
     * @param target  the target item stack to look for in the bundles
     * @return the difference in item amounts between the two bundles for the given target item stack
     */
    public static int getBundleDifference(ItemStack bundleA, ItemStack bundleB, ItemStack target) {
        var contentsA = bundleA.getData(DataComponentTypes.BUNDLE_CONTENTS);
        var contentsB = bundleB.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (contentsA == null || contentsB == null)
            return 0;
        
        var countA = contentsA.contents().stream()
            .filter(is -> is.isSimilar(target))
            .mapToInt(ItemStack::getAmount)
            .sum();
        var countB = contentsB.contents().stream()
            .filter(is -> is.isSimilar(target))
            .mapToInt(ItemStack::getAmount)
            .sum();
        
        return countA - countB;
    }
    
    /**
     * Gets the maximum amount of items from {@code target} that can fit into
     * {@code bundle} according to the vanilla bundle weight formula.
     */
    public static int getMaxAmountToAddToBundle(ItemStack bundle, ItemStack target) {
        if (ItemUtils.isEmpty(target) || !canFitInsideContainerItems(target))
            return 0;

        var contents = bundle.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (contents == null)
            return 0;

        int usedWeight = 0;
        for (var stack : contents.contents()) {
            usedWeight += stack.getAmount() * unitWeight64ths(stack);
        }
        int remainingWeight = 64 - usedWeight;
        if (remainingWeight <= 0)
            return 0;

        int perTargetWeight = unitWeight64ths(target);
        return Math.min(target.getAmount(), remainingWeight / perTargetWeight);
    }

    /**
     * Weight of a single item of {@code stack} in 1/64ths of a bundle, following vanilla:
     * nested bundles weigh 4/64 plus their contents, items carrying bees fill the whole
     * bundle, anything else weighs 1/maxStackSize. Non-divisor max stack sizes (only
     * reachable through a custom max_stack_size component) are rounded up so the bundle
     * can never be overfilled.
     */
    private static int unitWeight64ths(ItemStack stack) {
        var nested = stack.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (nested != null) {
            int weight = 4;
            for (var content : nested.contents()) {
                weight += content.getAmount() * unitWeight64ths(content);
            }
            return weight;
        }
        if (carriesBees(stack))
            return 64;
        int maxStackSize = Math.max(1, stack.getMaxStackSize());
        return (64 + maxStackSize - 1) / maxStackSize;
    }

    private static boolean carriesBees(ItemStack stack) {
        var type = stack.getType();
        if (type != Material.BEEHIVE && type != Material.BEE_NEST)
            return false;
        return stack.getItemMeta() instanceof BlockStateMeta meta
            && meta.hasBlockState()
            && meta.getBlockState() instanceof EntityBlockStorage<?> storage
            && storage.getEntityCount() > 0;
    }

    /**
     * Mirror of vanilla {@code Item#canFitInsideContainerItems}, which Bukkit does not
     * expose: only shulker boxes are excluded from bundles.
     */
    private static boolean canFitInsideContainerItems(ItemStack stack) {
        return !stack.getType().getKey().getKey().endsWith("shulker_box");
    }
    
    /**
     * Gets the first item stack from the bundle without removing it.
     *
     * @param bundle the bundle item stack
     * @return the first item stack in the bundle, or null if there is none
     */
    public static @Nullable ItemStack getFirstFromBundle(ItemStack bundle) {
        var bundleContents = bundle.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundleContents == null)
            return null;
        
        var contents = bundleContents.contents();
        return contents.isEmpty() ? null : contents.getFirst();
    }
    
    /**
     * Updates the given bundle by removing target's amount of items similar to target from the bundle.
     *
     * @param bundle the bundle item stack
     * @param target the target item stack
     * @return The remaining amount of items that were not in the bundle and could not be removed.
     */
    public static int removeFromBundle(ItemStack bundle, ItemStack target) {
        if (ItemUtils.isEmpty(bundle) || ItemUtils.isEmpty(target))
            return target.getAmount();
        
        var bundleContents = bundle.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundleContents == null)
            return target.getAmount();
        
        int amountLeft = target.getAmount();
        var contents = new ArrayList<>(bundleContents.contents());
        var iterator = contents.iterator();
        while (iterator.hasNext() && amountLeft > 0) {
            var itemStack = iterator.next();
            if (itemStack.isSimilar(target)) {
                int amount = itemStack.getAmount();
                if (amount > amountLeft) {
                    itemStack.setAmount(amount - amountLeft);
                    amountLeft = 0;
                } else {
                    iterator.remove();
                    amountLeft -= amount;
                }
            }
        }
        
        bundle.setData(DataComponentTypes.BUNDLE_CONTENTS, bundleContents(contents));
        return amountLeft;
    }
    
    /**
     * Removes the item at the bundle's selected slot and returns it, resetting the selection.
     *
     * @param bundle the bundle item stack
     * @return the removed item stack, or null if the bundle is empty or has no contents
     */
    public static @Nullable ItemStack takeSelectedFromBundle(ItemStack bundle) {
        var bundleContents = bundle.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundleContents == null)
            return null;

        var contents = new ArrayList<>(bundleContents.contents());
        if (contents.isEmpty())
            return null;

        int index = Math.clamp(getSelectedBundleSlot(bundle), 0, contents.size() - 1);
        ItemStack taken = contents.remove(index);

        bundle.setData(DataComponentTypes.BUNDLE_CONTENTS, bundleContents(contents));
        clearSelectedBundleSlot(bundle);
        return taken;
    }

    /**
     * Sets the selected bundle slot, persisting it in the item (see {@link #BUNDLE_SELECTED_SLOT_KEY}).
     */
    public static void setSelectedBundleSlot(ItemStack bundle, int bundleSlot) {
        bundle.editPersistentDataContainer(pdc -> pdc.set(BUNDLE_SELECTED_SLOT_KEY, PersistentDataType.INTEGER, bundleSlot));
    }

    private static int getSelectedBundleSlot(ItemStack bundle) {
        Integer selected = bundle.getPersistentDataContainer().get(BUNDLE_SELECTED_SLOT_KEY, PersistentDataType.INTEGER);
        return selected == null ? 0 : selected;
    }

    private static void clearSelectedBundleSlot(ItemStack bundle) {
        bundle.editPersistentDataContainer(pdc -> pdc.remove(BUNDLE_SELECTED_SLOT_KEY));
    }

}
