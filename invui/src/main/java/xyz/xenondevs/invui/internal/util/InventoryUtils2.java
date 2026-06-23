package xyz.xenondevs.invui.internal.util;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.world.inventory.MenuType;
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

    public static int getSizeOf(MenuType<?> type) {
        if (type == MenuType.GENERIC_9x1) return 9;
        if (type == MenuType.GENERIC_9x2) return 18;
        if (type == MenuType.GENERIC_9x3) return 27;
        if (type == MenuType.GENERIC_9x4) return 36;
        if (type == MenuType.GENERIC_9x5) return 45;
        if (type == MenuType.GENERIC_9x6) return 54;
        if (type == MenuType.GENERIC_3x3) return 9;
        if (type == MenuType.CRAFTER_3x3) return 10;
        if (type == MenuType.ANVIL) return 3;
        if (type == MenuType.BEACON) return 1;
        if (type == MenuType.BLAST_FURNACE) return 3;
        if (type == MenuType.BREWING_STAND) return 5;
        if (type == MenuType.CRAFTING) return 10;
        if (type == MenuType.ENCHANTMENT) return 2;
        if (type == MenuType.FURNACE) return 3;
        if (type == MenuType.GRINDSTONE) return 3;
        if (type == MenuType.HOPPER) return 5;
        if (type == MenuType.LECTERN) return 1;
        if (type == MenuType.LOOM) return 4;
        if (type == MenuType.MERCHANT) return 3;
        if (type == MenuType.SHULKER_BOX) return 27;
        if (type == MenuType.SMITHING) return 4;
        if (type == MenuType.SMOKER) return 3;
        if (type == MenuType.CARTOGRAPHY_TABLE) return 3;
        if (type == MenuType.STONECUTTER) return 2;
        throw new UnsupportedOperationException("Unsupported menu type: " + type);
    }

    public static int getDataSlotCountOf(MenuType<?> type) {
        if (type == MenuType.CRAFTER_3x3) return 9;
        if (type == MenuType.ANVIL) return 1;
        if (type == MenuType.BEACON) return 3;
        if (type == MenuType.FURNACE || type == MenuType.BLAST_FURNACE || type == MenuType.SMOKER) return 4;
        if (type == MenuType.BREWING_STAND) return 2;
        if (type == MenuType.ENCHANTMENT) return 10;
        if (type == MenuType.LECTERN) return 1;
        if (type == MenuType.LOOM) return 1;
        if (type == MenuType.STONECUTTER) return 1;
        return 0;
    }

    @SuppressWarnings("SwitchStatementWithTooFewBranches")
    public static MenuType<?> getMatchingGenericMenuType(int width, int height) {
        return switch (width) {
            case 3 -> switch (height) {
                case 3 -> MenuType.GENERIC_3x3;
                default -> throw new IllegalArgumentException("Illegal height: " + height + " for width 3");
            };
            case 5 -> switch (height) {
                case 1 -> MenuType.HOPPER;
                default -> throw new IllegalArgumentException("Illegal height: " + height + " for width 5");
            };
            case 9 -> switch (height) {
                case 1 -> MenuType.GENERIC_9x1;
                case 2 -> MenuType.GENERIC_9x2;
                case 3 -> MenuType.GENERIC_9x3;
                case 4 -> MenuType.GENERIC_9x4;
                case 5 -> MenuType.GENERIC_9x5;
                case 6 -> MenuType.GENERIC_9x6;
                default -> throw new IllegalArgumentException("Illegal height: " + height + " for width 9");
            };
            default -> throw new IllegalArgumentException("Illegal width: " + width);
        };
    }

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
