package xyz.xenondevs.invui.internal.util;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntList;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.inventory.Inventory;
import xyz.xenondevs.invui.inventory.OperationCategory;
import xyz.xenondevs.invui.inventory.ReferencingInventory;
import xyz.xenondevs.invui.util.ItemUtils;

public final class InventoryUtils2 {

    private InventoryUtils2() {}

    public static Gui createPlayerReferencingInventoryGui(Player player) {
        Inventory inv = ReferencingInventory.fromPlayerStorageContents(player.getInventory());
        inv.reverseIterationOrder(OperationCategory.ADD);
        inv.setGuiPriority(OperationCategory.ADD, Integer.MAX_VALUE);
        inv.setGuiPriority(OperationCategory.COLLECT, Integer.MIN_VALUE);
        return Gui.of(9, 4, inv);
    }

    public static Int2ObjectMap<ItemStack> simulateItemDrag(ClickType clickType, InventoryView view, IntList slots, ItemStack cursor) {
        if (ItemUtils.isEmpty(cursor) || slots.isEmpty())
            return new Int2ObjectOpenHashMap<>();

        var result = new Int2ObjectOpenHashMap<ItemStack>();
        if (clickType == ClickType.LEFT || clickType == ClickType.RIGHT) {
            int amountPerSlot = clickType == ClickType.LEFT ? cursor.getAmount() / slots.size() : 1;
            for (int slot : slots) {
                if (cursor.getAmount() <= 0)
                    break;

                var itemThere = view.getItem(slot);
                if (itemThere == null || (itemThere.isSimilar(cursor) && itemThere.getAmount() < itemThere.getMaxStackSize())) {
                    var currentAmount = ItemUtils.getAmount(itemThere);
                    var newAmount = Math.min(cursor.getMaxStackSize(), currentAmount + amountPerSlot);
                    var toPut = ItemUtils.cloneWithCount(cursor, newAmount);
                    assert toPut != null;

                    result.put(slot, toPut);
                    cursor.setAmount(cursor.getAmount() - (newAmount - currentAmount));
                }
            }
        } else if (clickType == ClickType.MIDDLE) {
            for (int slot : slots) {
                var itemThere = view.getItem(slot);
                if (itemThere == null || (itemThere.isSimilar(cursor) && itemThere.getAmount() < itemThere.getMaxStackSize())) {
                    var toPut = ItemUtils.cloneWithCount(cursor, cursor.getMaxStackSize());
                    assert toPut != null;

                    view.setItem(slot, toPut);
                }
            }
            cursor.setAmount(0);
        }

        return result;
    }

}
