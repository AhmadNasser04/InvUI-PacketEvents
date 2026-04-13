package xyz.xenondevs.invui.internal.menu;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientNameItem;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import xyz.xenondevs.invui.internal.network.PacketListener;

import java.util.function.Consumer;

/**
 * A packet-based anvil menu.
 */
public class CustomAnvilMenu extends CustomContainerMenu {

    private static final int ENCHANTMENT_COST_DIRTY_MARKER = Integer.MIN_VALUE;

    private String renameText = "";
    private @Nullable Consumer<? super String> renameHandler;

    /**
     * Creates a new {@link CustomAnvilMenu} for the specified viewer.
     *
     * @param player The player that will view the menu
     */
    public CustomAnvilMenu(Player player) {
        super(MenuType.ANVIL, player);
    }

    @Override
    public void open(Component title) {
        PacketListener.getInstance().redirectIncoming(
            player,
            PacketType.Play.Client.NAME_ITEM,
            WrapperPlayClientNameItem::new,
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
        PacketListener.getInstance().removeRedirect(player, PacketType.Play.Client.NAME_ITEM);
        super.handleClosed(cause);
    }

    @Override
    protected UpdateType processPacket(PacketWrapper<?> packet) {
        if (packet instanceof WrapperPlayClientNameItem nameItem) {
            handleRename(nameItem);
            return UpdateType.DIRTY;
        }
        return super.processPacket(packet);
    }

    @Override
    public void setItem(int slot, @Nullable ItemStack item) {
        super.setItem(slot, item);

        // updating second input slot causes client-side prediction of enchantment cost
        if (slot == 1) {
            remoteDataSlots[0] = ENCHANTMENT_COST_DIRTY_MARKER;
        }
    }

    private void handleRename(WrapperPlayClientNameItem packet) {
        renameText = packet.getItemName();
        if (renameHandler != null)
            renameHandler.accept(renameText);
        markSlotDirty(2);
        remoteDataSlots[0] = ENCHANTMENT_COST_DIRTY_MARKER;
    }

    /**
     * Sets the rename handler that is called when the input text changes.
     *
     * @param renameHandler The rename handler to set.
     */
    public void setRenameHandler(Consumer<? super String> renameHandler) {
        this.renameHandler = renameHandler;
    }

    /**
     * Gets the current text in the rename field.
     */
    public String getRenameText() {
        return renameText;
    }

    /**
     * Gets the enchantment cost.
     */
    public int getEnchantmentCost() {
        return dataSlots[0];
    }

    /**
     * Sets the enchantment cost.
     */
    public void setEnchantmentCost(int enchantmentCost) {
        dataSlots[0] = enchantmentCost;
    }
}
