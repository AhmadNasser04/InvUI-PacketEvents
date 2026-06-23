package xyz.xenondevs.invui.internal.menu;

import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.item.mapdecoration.MapDecorationType;
import com.github.retrooper.packetevents.protocol.item.mapdecoration.MapDecorationTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData.MapDecoration;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.jspecify.annotations.Nullable;
import xyz.xenondevs.invui.internal.network.PacketListener;
import xyz.xenondevs.invui.internal.util.MathUtils;
import xyz.xenondevs.invui.util.ItemUtils;
import xyz.xenondevs.invui.window.CartographyWindow;
import xyz.xenondevs.invui.window.CartographyWindow.MapIcon;
import xyz.xenondevs.invui.window.CartographyWindow.MapPatch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A packet-based cartography table menu.
 */
public class CustomCartographyMenu extends CustomContainerMenu {

    private static final int MAP_SIZE = 128;

    private int mapId = -MathUtils.RANDOM.nextInt(Integer.MAX_VALUE);
    private byte[] canvas = new byte[MAP_SIZE * MAP_SIZE];
    private final Set<MapIcon> icons = new HashSet<>();
    private CartographyWindow.View view = CartographyWindow.View.NORMAL;

    public CustomCartographyMenu(Player player) {
        super(MenuType.CARTOGRAPHY_TABLE, player);
    }

    @Override
    public void setItem(int slot, @Nullable ItemStack item) {
        if (slot == 0 && !ItemUtils.isEmpty(item)) {
            // Stamp the input map slot with our virtual map id so the client
            // associates this slot with the canvas we stream below.
            ItemStack clone = item.clone();
            var pe = SpigotConversionUtil.fromBukkitItemStack(clone);
            pe.setComponent(ComponentTypes.MAP_ID, mapId);
            super.setItem(slot, SpigotConversionUtil.toBukkitItemStack(pe));
        } else if (slot == 1 && item != null) {
            var targetType = switch (view) {
                case NORMAL -> ItemType.STONE;
                case SMALL -> ItemType.PAPER;
                case DUPLICATE -> ItemType.MAP;
                case LOCK -> ItemType.GLASS_PANE;
            };
            super.setItem(slot, ItemUtils.asType(item, targetType));
        } else {
            super.setItem(slot, item);
        }
    }

    @Override
    public void open(Component title) {
        super.open(title);
        sendMapUpdate(canvas, 0, 0, MAP_SIZE, MAP_SIZE, icons);
    }

    public void setView(CartographyWindow.View view) {
        this.view = view;
        // Force slot 1 to re-render with the new view marker.
        ItemStack current = getItem(1);
        if (current != null) setItem(1, current);
    }

    public CartographyWindow.View getView() {
        return view;
    }

    public void setIcons(Collection<? extends MapIcon> icons, boolean sendUpdate) {
        this.icons.clear();
        this.icons.addAll(icons);
        if (sendUpdate) {
            sendMapUpdate(null, 0, 0, 0, 0, this.icons);
        }
    }

    public void applyPatch(MapPatch patch, boolean sendUpdate) {
        if (patch.startX() + patch.width() > MAP_SIZE || patch.startY() + patch.height() > MAP_SIZE)
            throw new IllegalArgumentException("Map patch is out of bounds");

        for (int y = 0; y < patch.height(); y++) {
            for (int x = 0; x < patch.width(); x++) {
                int i = (patch.startY() + y) * MAP_SIZE + patch.startX() + x;
                canvas[i] = patch.colors()[y * patch.width() + x];
            }
        }

        if (sendUpdate) {
            sendMapUpdate(patch.colors(), patch.startX(), patch.startY(), patch.width(), patch.height(), null);
        }
    }

    public void resetMap() {
        mapId = -MathUtils.RANDOM.nextInt(Integer.MAX_VALUE);
        canvas = new byte[MAP_SIZE * MAP_SIZE];
        icons.clear();
        // Force slot 0 to be re-stamped with the new map id.
        ItemStack current = getItem(0);
        if (current != null) setItem(0, current);
    }

    /**
     * Sends a map data packet. Pass {@code data == null} for "no canvas
     * update, decorations only"; pass {@code icons == null} for "canvas
     * patch only, leave decorations alone".
     */
    private void sendMapUpdate(
        byte @Nullable [] data,
        int x, int z, int width, int height,
        @Nullable Collection<MapIcon> iconsToSend
    ) {
        @Nullable List<MapDecoration> decorations = iconsToSend == null
            ? null
            : new ArrayList<>(iconsToSend.stream().map(CustomCartographyMenu::toPeDecoration).toList());

        var packet = new WrapperPlayServerMapData(
            mapId,
            (byte) 0,    // scale
            false,       // tracking position
            false,       // locked
            decorations,
            width,
            height,
            x,
            z,
            data
        );
        PacketListener.getInstance().injectOutgoing(player, packet);
    }

    private static MapDecoration toPeDecoration(MapIcon icon) {
        // CartographyWindow.MapIcon coordinates are 0..256 (origin top-left);
        // wire coordinates are signed bytes (-128..127, origin map center).
        byte x = (byte) (icon.x() - 128);
        byte y = (byte) (icon.y() - 128);
        Component name = icon.component();
        return new MapDecoration(toPeDecorationType(icon.type()), x, y, (byte) icon.rot(), name);
    }

    private static MapDecorationType toPeDecorationType(MapIcon.Type type) {
        return switch (type) {
            case WHITE_ARROW -> MapDecorationTypes.PLAYER;
            case GREEN_ARROW -> MapDecorationTypes.FRAME;
            case RED_ARROW -> MapDecorationTypes.RED_MARKER;
            case BLUE_ARROW -> MapDecorationTypes.BLUE_MARKER;
            case WHITE_CROSS -> MapDecorationTypes.TARGET_X;
            case RED_POINTER -> MapDecorationTypes.TARGET_POINT;
            case WHITE_CIRCLE -> MapDecorationTypes.PLAYER_OFF_MAP;
            case SMALL_WHITE_CIRCLE -> MapDecorationTypes.PLAYER_OFF_LIMITS;
            case MANSION -> MapDecorationTypes.MANSION;
            case TEMPLE -> MapDecorationTypes.JUNGLE_TEMPLE;
            case WHITE_BANNER -> MapDecorationTypes.BANNER_WHITE;
            case ORANGE_BANNER -> MapDecorationTypes.BANNER_ORANGE;
            case MAGENTA_BANNER -> MapDecorationTypes.BANNER_MAGENTA;
            case LIGHT_BLUE_BANNER -> MapDecorationTypes.BANNER_LIGHT_BLUE;
            case YELLOW_BANNER -> MapDecorationTypes.BANNER_YELLOW;
            case LIME_BANNER -> MapDecorationTypes.BANNER_LIME;
            case PINK_BANNER -> MapDecorationTypes.BANNER_PINK;
            case GRAY_BANNER -> MapDecorationTypes.BANNER_GRAY;
            case LIGHT_GRAY_BANNER -> MapDecorationTypes.BANNER_LIGHT_GRAY;
            case CYAN_BANNER -> MapDecorationTypes.BANNER_CYAN;
            case PURPLE_BANNER -> MapDecorationTypes.BANNER_PURPLE;
            case BLUE_BANNER -> MapDecorationTypes.BANNER_BLUE;
            case BROWN_BANNER -> MapDecorationTypes.BANNER_BROWN;
            case GREEN_BANNER -> MapDecorationTypes.BANNER_GREEN;
            case RED_BANNER -> MapDecorationTypes.BANNER_RED;
            case BLACK_BANNER -> MapDecorationTypes.BANNER_BLACK;
            case RED_CROSS -> MapDecorationTypes.RED_X;
        };
    }

}
