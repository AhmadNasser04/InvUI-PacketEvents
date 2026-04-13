package xyz.xenondevs.invui.internal.menu;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSlotStateChange;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jspecify.annotations.Nullable;
import xyz.xenondevs.invui.internal.network.PacketListener;

import java.util.function.BiConsumer;

/**
 * A packet-based crafter menu.
 */
public class CustomCrafterMenu extends CustomContainerMenu {

    private @Nullable BiConsumer<? super Integer, ? super Boolean> slotStateChangeHandler;

    public CustomCrafterMenu(Player player) {
        super(MenuType.CRAFTER_3x3, player);
    }

    @Override
    public void open(Component title) {
        PacketListener.getInstance().redirectIncoming(
            player,
            PacketType.Play.Client.SLOT_STATE_CHANGE,
            WrapperPlayClientSlotStateChange::new,
            incoming
        );
        super.open(title);
    }

    @Override
    public void handleClosed() {
        handleClosed(InventoryCloseEvent.Reason.UNKNOWN);
    }

    @Override
    public void handleClosed(InventoryCloseEvent.Reason cause) {
        PacketListener.getInstance().removeRedirect(player, PacketType.Play.Client.SLOT_STATE_CHANGE);
        super.handleClosed(cause);
    }

    @Override
    protected UpdateType processPacket(PacketWrapper<?> packet) {
        if (packet instanceof WrapperPlayClientSlotStateChange slotStateChange) {
            handleSlotStateChange(slotStateChange);
            return UpdateType.NONE;
        }
        return super.processPacket(packet);
    }

    private void handleSlotStateChange(WrapperPlayClientSlotStateChange packet) {
        if (packet.getWindowId() != containerId)
            return;
        int slot = packet.getSlot();
        if (slot < 0 || slot >= dataSlots.length)
            return;

        int value = packet.isState() ? 0 : 1;
        remoteDataSlots[slot] = value;
        dataSlots[slot] = value;

        if (slotStateChangeHandler != null)
            slotStateChangeHandler.accept(slot, !packet.isState());
    }

    /**
     * Returns whether the specified slot is disabled.
     */
    public boolean isSlotDisabled(int slot) {
        return dataSlots[slot] == 1;
    }

    /**
     * Sets the disabled state of the specified slot.
     */
    public void setSlotDisabled(int slot, boolean state) {
        dataSlots[slot] = state ? 1 : 0;
        markSlotDirty(slot);
    }

    public void setSlotStateChangeHandler(BiConsumer<? super Integer, ? super Boolean> handler) {
        this.slotStateChangeHandler = handler;
    }

}
