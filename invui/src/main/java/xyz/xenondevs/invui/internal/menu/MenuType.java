package xyz.xenondevs.invui.internal.menu;

import com.github.retrooper.packetevents.manager.server.ServerVersion;

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

    /**
     * Resolves the vanilla menu-registry id for this type on the given server version.
     * <p>
     * The constants are declared in the same order as the vanilla {@code minecraft:menu}
     * registry, so the enum ordinal equals the registry id on 1.21+. The only shift in the
     * supported range is {@code CRAFTER_3x3}, which was inserted at index 7 in 1.21; on older
     * versions every type after it is offset by one.
     */
    public int idFor(ServerVersion version) {
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
