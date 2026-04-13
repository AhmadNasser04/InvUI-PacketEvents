package xyz.xenondevs.invui.internal.menu;

import org.bukkit.entity.Player;

/**
 * A packet-based crafting table menu.
 */
public class CustomCraftingTableMenu extends CustomRecipeBookPoweredMenu {

    /**
     * Creates a new {@link CustomCraftingTableMenu} for the specified viewer.
     *
     * @param player The player that will view the menu
     */
    public CustomCraftingTableMenu(Player player) {
        super(MenuType.CRAFTING, player);
    }

}
