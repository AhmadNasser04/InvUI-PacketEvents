package xyz.xenondevs.invui.internal.menu;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import net.minecraft.core.registries.BuiltInRegistries;

public enum MenuType {
    GENERIC_9x1,
    GENERIC_9x2,
    GENERIC_9x3,
    GENERIC_9x4,
    GENERIC_9x5,
    GENERIC_9x6,
    GENERIC_3x3,
    /** Added in 1.21; not available on 1.20.5. */
    CRAFTER_3x3,
    ANVIL,
    BEACON,
    BLAST_FURNACE,
    BREWING_STAND,
    CRAFTING,
    ENCHANTMENT,
    FURNACE,
    GRINDSTONE,
    HOPPER,
    LECTERN,
    LOOM,
    MERCHANT,
    SHULKER_BOX,
    SMITHING,
    SMOKER,
    CARTOGRAPHY_TABLE,
    STONECUTTER;

    public int idFor(ServerVersion version) {
        int registryId = BuiltInRegistries.MENU.getId(toNms());
        if (registryId >= 0)
            return registryId;

        int base = baseId();
        if (version.isOlderThan(ServerVersion.V_1_21)) {
            if (this == CRAFTER_3x3)
                throw new UnsupportedOperationException("CRAFTER_3x3 is not available before 1.21");
            if (this.ordinal() > CRAFTER_3x3.ordinal())
                return base - 1;
        }
        return base;
    }

    private int baseId() {
        return this.ordinal();
    }

    public int size() {
        return switch (this) {
            case GENERIC_9x1 -> 9;
            case GENERIC_9x2 -> 18;
            case GENERIC_9x3 -> 27;
            case GENERIC_9x4 -> 36;
            case GENERIC_9x5 -> 45;
            case GENERIC_9x6 -> 54;
            case GENERIC_3x3 -> 9;
            case CRAFTER_3x3 -> 10;
            case ANVIL, FURNACE -> 3;
            case BEACON -> 1;
            case BLAST_FURNACE -> 3;
            case BREWING_STAND -> 5;
            case CRAFTING -> 10;
            case ENCHANTMENT -> 2;
            case GRINDSTONE -> 3;
            case HOPPER -> 5;
            case LECTERN -> 1;
            case LOOM -> 4;
            case MERCHANT -> 3;
            case SHULKER_BOX -> 27;
            case SMITHING -> 4;
            case SMOKER -> 3;
            case CARTOGRAPHY_TABLE -> 3;
            case STONECUTTER -> 2;
        };
    }

    public int dataSlotCount() {
        return switch (this) {
            case CRAFTER_3x3 -> 9;
            case ANVIL -> 1;
            case BEACON -> 3;
            case FURNACE, BLAST_FURNACE, SMOKER -> 4;
            case BREWING_STAND -> 2;
            case ENCHANTMENT -> 10;
            case LECTERN -> 1;
            case LOOM -> 1;
            case STONECUTTER -> 1;
            default -> 0;
        };
    }

    public net.minecraft.world.inventory.MenuType<?> toNms() {
        return switch (this) {
            case GENERIC_9x1 -> net.minecraft.world.inventory.MenuType.GENERIC_9x1;
            case GENERIC_9x2 -> net.minecraft.world.inventory.MenuType.GENERIC_9x2;
            case GENERIC_9x3 -> net.minecraft.world.inventory.MenuType.GENERIC_9x3;
            case GENERIC_9x4 -> net.minecraft.world.inventory.MenuType.GENERIC_9x4;
            case GENERIC_9x5 -> net.minecraft.world.inventory.MenuType.GENERIC_9x5;
            case GENERIC_9x6 -> net.minecraft.world.inventory.MenuType.GENERIC_9x6;
            case GENERIC_3x3 -> net.minecraft.world.inventory.MenuType.GENERIC_3x3;
            case CRAFTER_3x3 -> net.minecraft.world.inventory.MenuType.CRAFTER_3x3;
            case ANVIL -> net.minecraft.world.inventory.MenuType.ANVIL;
            case BEACON -> net.minecraft.world.inventory.MenuType.BEACON;
            case BLAST_FURNACE -> net.minecraft.world.inventory.MenuType.BLAST_FURNACE;
            case BREWING_STAND -> net.minecraft.world.inventory.MenuType.BREWING_STAND;
            case CRAFTING -> net.minecraft.world.inventory.MenuType.CRAFTING;
            case ENCHANTMENT -> net.minecraft.world.inventory.MenuType.ENCHANTMENT;
            case FURNACE -> net.minecraft.world.inventory.MenuType.FURNACE;
            case GRINDSTONE -> net.minecraft.world.inventory.MenuType.GRINDSTONE;
            case HOPPER -> net.minecraft.world.inventory.MenuType.HOPPER;
            case LECTERN -> net.minecraft.world.inventory.MenuType.LECTERN;
            case LOOM -> net.minecraft.world.inventory.MenuType.LOOM;
            case MERCHANT -> net.minecraft.world.inventory.MenuType.MERCHANT;
            case SHULKER_BOX -> net.minecraft.world.inventory.MenuType.SHULKER_BOX;
            case SMITHING -> net.minecraft.world.inventory.MenuType.SMITHING;
            case SMOKER -> net.minecraft.world.inventory.MenuType.SMOKER;
            case CARTOGRAPHY_TABLE -> net.minecraft.world.inventory.MenuType.CARTOGRAPHY_TABLE;
            case STONECUTTER -> net.minecraft.world.inventory.MenuType.STONECUTTER;
        };
    }

    public static MenuType matchingGeneric(int width, int height) {
        if (width == 3 && height == 3) return GENERIC_3x3;
        if (width == 5 && height == 1) return HOPPER;
        if (width == 9) {
            return switch (height) {
                case 1 -> GENERIC_9x1;
                case 2 -> GENERIC_9x2;
                case 3 -> GENERIC_9x3;
                case 4 -> GENERIC_9x4;
                case 5 -> GENERIC_9x5;
                case 6 -> GENERIC_9x6;
                default -> throw new IllegalArgumentException("Illegal height " + height + " for width 9");
            };
        }
        throw new IllegalArgumentException("No matching generic menu for " + width + "x" + height);
    }

}
