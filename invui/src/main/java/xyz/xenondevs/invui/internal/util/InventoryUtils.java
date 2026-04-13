package xyz.xenondevs.invui.internal.util;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntList;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.inventory.Inventory;
import xyz.xenondevs.invui.inventory.OperationCategory;
import xyz.xenondevs.invui.inventory.ReferencingInventory;
import xyz.xenondevs.invui.util.ItemUtils;

public class InventoryUtils {

    /**
     * Spawns an item entity as if the player dropped it, also firing {@link PlayerDropItemEvent}.
     *
     * @param player    The player
     * @param itemStack The item stack
     * @return Whether the item was dropped. False if the event was cancelled.
     */
    @SuppressWarnings("UnstableApiUsage")
    public static boolean dropItemLikePlayer(Player player, @Nullable ItemStack itemStack) {
        if (ItemUtils.isEmpty(itemStack))
            return true;
        
        Location location = player.getLocation();
        location.add(0, 1.5, 0); // not the eye location
        
        Item item = location.getWorld().createEntity(location, Item.class);
        item.setItemStack(itemStack.clone());
        item.setPickupDelay(40);
        item.setVelocity(location.getDirection().multiply(0.35));
        
        if (new PlayerDropItemEvent(player, item).callEvent()) {
            location.getWorld().addEntity(item);
            return true;
        }
        
        return false;
    }
    
    /**
     * Adds an item stack to the player's inventory or drops it if it doesn't fit.
     * Also fires {@link PlayerDropItemEvent}, effectively deleting the item if the event is cancelled.
     *
     * @param player    The player
     * @param itemStack The item stack
     */
    public static void addToInventoryOrDrop(Player player, @Nullable ItemStack itemStack) {
        if (ItemUtils.isEmpty(itemStack))
            return;

        player.getInventory().addItem(itemStack.clone())
            .entrySet()
            .stream()
            .findFirst()
            .ifPresent(entry -> dropItemLikePlayer(player, entry.getValue()));
    }
    
    /**
     * Creates a new gui that references the player's inventory and handles interactions in the same way
     * that the player inventory does.
     *
     * @param player The player whose inventory to reference
     * @return The new gui that references the player's inventory
     */
    public static Gui createPlayerReferencingInventoryGui(Player player) {
        Inventory inv = ReferencingInventory.fromPlayerStorageContents(player.getInventory());
        inv.reverseIterationOrder(OperationCategory.ADD); // shift-clicking moves to bottom right
        inv.setGuiPriority(OperationCategory.ADD, Integer.MAX_VALUE); // shift-click always moves between upper and lower inv
        inv.setGuiPriority(OperationCategory.COLLECT, Integer.MIN_VALUE); // double-click collects from lower inv last
        return Gui.of(9, 4, inv);
    }
    
    /**
     * Simulates an item drag operation.
     *
     * @param clickType The click type of the drag
     * @param view      The inventory view
     * @param slots     The slots involved in the drag
     * @param cursor    The current cursor item stack. Will be modified to reflect the remaining items after the drag.
     * @return A map of slot indices to the resulting item stacks in those slots after the drag
     */
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
