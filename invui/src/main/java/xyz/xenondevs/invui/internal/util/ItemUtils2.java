package xyz.xenondevs.invui.internal.util;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import xyz.xenondevs.invui.util.ItemUtils;

import java.util.ArrayList;
import java.util.List;

import static io.papermc.paper.datacomponent.item.BundleContents.bundleContents;

@SuppressWarnings("UnstableApiUsage")
public final class ItemUtils2 {
    
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
     * stacks appropriately. The vanilla weight formula ({@code sum(amount *
     * 64 / maxStackSize) <= 64}) is applied.
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
        if (ItemUtils.isEmpty(target))
            return 0;

        var contents = bundle.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (contents == null)
            return 0;

        // Vanilla weight: each item's weight = 64 / maxStackSize. Bundle capacity = 64.
        int usedWeight = 0;
        for (var stack : contents.contents()) {
            int perItem = 64 / Math.max(1, stack.getMaxStackSize());
            usedWeight += stack.getAmount() * perItem;
        }
        int remainingWeight = 64 - usedWeight;
        if (remainingWeight <= 0)
            return 0;

        int perTargetWeight = 64 / Math.max(1, target.getMaxStackSize());
        if (perTargetWeight <= 0)
            return target.getAmount();

        return Math.min(target.getAmount(), remainingWeight / perTargetWeight);
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
    
    public static @Nullable ItemStack takeSelectedFromBundle(ItemStack bundle) {
        return NmsBridge.takeBundleSelected(bundle);
    }

    /**
     * Sets the selected bundle slot.
     */
    public static void setSelectedBundleSlot(ItemStack bundle, int bundleSlot) {
        NmsBridge.setBundleSelectedIndex(bundle, bundleSlot);
    }
    
}
