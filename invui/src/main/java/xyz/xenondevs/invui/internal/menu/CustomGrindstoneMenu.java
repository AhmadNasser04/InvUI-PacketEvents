package xyz.xenondevs.invui.internal.menu;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A packet-based grindstone menu.
 */
public class CustomGrindstoneMenu extends CustomContainerMenu {

    /**
     * Creates a new {@link CustomGrindstoneMenu} for the specified player.
     *
     * @param player The player that will view the menu
     */
    public CustomGrindstoneMenu(Player player) {
        super(MenuType.GRINDSTONE, player);
    }

    @Override
    public void setItem(int slot, @Nullable ItemStack item) {
        super.setItem(slot, item);

        // client-side prediction clears output slot when input slots are modified
        if (slot == 0 || slot == 1) {
            markSlotDirty(2);
        }
    }
}
