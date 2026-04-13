package xyz.xenondevs.invui.internal.menu;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.mapper.MappedEntitySet;
import com.github.retrooper.packetevents.protocol.recipe.SingleInputOptionDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.ItemStackSlotDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.SlotDisplay;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDeclareRecipes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import xyz.xenondevs.invui.internal.network.PacketListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A custom stonecutter menu that allows for custom buttons by sending client-side recipes.
 */
public class CustomStonecutterMenu extends CustomContainerMenu {

    private @Nullable BiConsumer<? super Integer, ? super Integer> clickHandler;

    public CustomStonecutterMenu(org.bukkit.entity.Player player) {
        super(MenuType.STONECUTTER, player);
        dataSlots[0] = -1;
    }

    @Override
    protected UpdateType handleClick(WrapperPlayClientClickWindow packet) {
        remoteDataSlots[0] = -1;
        var updateType = super.handleClick(packet);
        return UpdateType.DIRTY.or(updateType);
    }

    @Override
    public void setItem(int slot, @Nullable ItemStack item) {
        super.setItem(slot, item);
        if (slot == 0) {
            markSlotDirty(1);
        }
    }

    /**
     * Sets the buttons (recipes) of the stonecutter menu. Requires 1.21.2+.
     */
    public void setButtons(List<? extends @Nullable ItemStack> buttons) {
        ServerVersion version = PacketEvents.getAPI().getServerManager().getVersion();
        if (version.isOlderThan(ServerVersion.V_1_21_2)) {
            throw new UnsupportedOperationException(
                "CustomStonecutterMenu.setButtons requires Minecraft 1.21.2+ — " +
                "PacketEvents 2.12.0 does not model the legacy stonecutter recipe wire format.");
        }

        var input = allItemsInput();
        var displays = new ArrayList<SingleInputOptionDisplay>(buttons.size());
        for (ItemStack button : buttons) {
            if (button == null) continue;
            var pe = SpigotConversionUtil.fromBukkitItemStack(button);
            SlotDisplay<?> display = new ItemStackSlotDisplay(pe);
            displays.add(new SingleInputOptionDisplay(input, display));
        }

        // 1.21.2+ recipe declaration: empty itemSets (we don't override the
        // ingredient sets) + our stonecutter list.
        var packet = new WrapperPlayServerDeclareRecipes(
            Collections.emptyMap(),
            displays
        );
        PacketListener.getInstance().injectOutgoing(player, packet);

        // Force a refresh: clear input slot client-side then resend our state.
        var clearInput = new WrapperPlayServerSetSlot(containerId, incrementStateId(), 0,
            com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY);
        PacketListener.getInstance().injectOutgoing(player, clearInput);
        markSlotDirty(0);
        markSlotDirty(1);
        remoteDataSlots[0] = -1;
        sendChangesToRemote(-1);
    }

    private static MappedEntitySet<ItemType> allItemsInput() {
        var items = new ArrayList<ItemType>();
        for (ItemType type : ItemTypes.values()) {
            if (type != ItemTypes.AIR) {
                items.add(type);
            }
        }
        return new MappedEntitySet<>(items);
    }

    @Override
    public UpdateType handleButtonClick(int clicked) {
        int prev = dataSlots[0];
        dataSlots[0] = clicked;
        remoteDataSlots[0] = clicked;
        markSlotDirty(1);

        if (clickHandler != null)
            clickHandler.accept(prev, clicked);

        return UpdateType.DIRTY;
    }

    public int getSelectedSlot() {
        return dataSlots[0];
    }

    public void setSelectedSlot(int selectedSlot) {
        dataSlots[0] = selectedSlot;
    }

    public void setClickHandler(BiConsumer<? super Integer, ? super Integer> clickHandler) {
        this.clickHandler = clickHandler;
    }
}