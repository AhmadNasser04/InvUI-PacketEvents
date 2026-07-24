package xyz.xenondevs.invui.internal.menu;

import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.mapper.MappedEntitySet;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.recipe.SingleInputOptionDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.ItemStackSlotDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.SlotDisplay;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDeclareRecipes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.event.inventory.InventoryCloseEvent;
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

    /** Lazily built "any item" input set shared by all custom stonecutter declarations. */
    private static @Nullable MappedEntitySet<ItemType> allItemsInput;

    private @Nullable BiConsumer<? super Integer, ? super Integer> clickHandler;

    public CustomStonecutterMenu(org.bukkit.entity.Player player) {
        super(MenuType.STONECUTTER, player);
        dataSlots[0] = -1;
    }

    @Override
    public void open(Component title) {
        // vanilla recipe declarations (e.g. from a datapack reload) would replace the
        // custom buttons client-side; the latest declaration stays cached by the packet
        // listener and is restored in handleClosed
        PacketListener.getInstance().discard(player, PacketType.Play.Server.DECLARE_RECIPES);
        super.open(title);
    }

    @Override
    public void handleClosed(InventoryCloseEvent.Reason cause) {
        var pl = PacketListener.getInstance();
        pl.stopDiscard(player, PacketType.Play.Server.DECLARE_RECIPES);
        var vanilla = pl.getVanillaRecipeData();
        if (vanilla != null) {
            pl.injectOutgoing(player, new WrapperPlayServerDeclareRecipes(
                vanilla.itemSets(),
                vanilla.stonecutterRecipes()
            ));
        }
        super.handleClosed(cause);
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
     * Sets the buttons (recipes) of the stonecutter menu.
     */
    public void setButtons(List<? extends @Nullable ItemStack> buttons) {
        var input = getAllItemsInput();
        var displays = new ArrayList<SingleInputOptionDisplay>(buttons.size());
        for (ItemStack button : buttons) {
            if (button == null) continue;
            var pe = SpigotConversionUtil.fromBukkitItemStack(button);
            SlotDisplay<?> display = new ItemStackSlotDisplay(pe);
            displays.add(new SingleInputOptionDisplay(input, display));
        }

        // A recipe declaration wholesale-replaces the client's recipe state, so the real
        // item property sets (furnace inputs, smithing slots, ...) must be carried over
        // to avoid degrading unrelated menus client-side.
        var vanilla = PacketListener.getInstance().getVanillaRecipeData();
        var packet = new WrapperPlayServerDeclareRecipes(
            vanilla != null ? vanilla.itemSets() : Collections.emptyMap(),
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

    private static synchronized MappedEntitySet<ItemType> getAllItemsInput() {
        var input = allItemsInput;
        if (input == null) {
            var items = new ArrayList<ItemType>();
            for (ItemType type : ItemTypes.values()) {
                if (type != ItemTypes.AIR) {
                    items.add(type);
                }
            }
            input = new MappedEntitySet<>(items);
            allItemsInput = input;
        }
        return input;
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
