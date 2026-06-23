package xyz.xenondevs.invui.internal.menu;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCraftRecipeRequest;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCraftRecipeResponse;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jspecify.annotations.Nullable;
import xyz.xenondevs.invui.internal.network.PacketListener;

import java.util.function.Consumer;

/**
 * Abstract superclass for all custom container menus that have a recipe book.
 */
public abstract class CustomRecipeBookPoweredMenu extends CustomContainerMenu {

    private @Nullable Consumer<? super Key> recipeSelectHandler;

    protected CustomRecipeBookPoweredMenu(MenuType menuType, Player player) {
        super(menuType, player);
    }

    @Override
    public void open(Component title) {
        PacketListener.getInstance().redirectIncoming(
            player,
            PacketType.Play.Client.CRAFT_RECIPE_REQUEST,
            WrapperPlayClientCraftRecipeRequest::new,
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
        PacketListener.getInstance().removeRedirect(player, PacketType.Play.Client.CRAFT_RECIPE_REQUEST);
        super.handleClosed(cause);
    }

    @Override
    protected UpdateType processPacket(PacketWrapper<?> packet) {
        if (packet instanceof WrapperPlayClientCraftRecipeRequest request) {
            if (request.getWindowId() != containerId)
                return UpdateType.NONE;

            var key = request.getRecipeKey();
            if (key != null && recipeSelectHandler != null) {
                recipeSelectHandler.accept(Key.key(key.getNamespace(), key.getKey()));
            }
            return UpdateType.NONE;
        }
        return super.processPacket(packet);
    }

    /**
     * Displays a ghost recipe of the given id in the menu.
     *
     * @param id The recipe id
     */
    public void sendGhostRecipe(Key id) {
        ServerVersion version = PacketEvents.getAPI().getServerManager().getVersion();
        if (version.isNewerThanOrEquals(ServerVersion.V_1_21_2)) {
            // PacketEvents needs a RecipeDisplay here, which InvUI does not model yet.
            return;
        }

        var packet = new WrapperPlayServerCraftRecipeResponse(
            containerId,
            new ResourceLocation(id.namespace(), id.value())
        );
        PacketListener.getInstance().injectOutgoing(player, packet);
    }

    public void setRecipeClickHandler(Consumer<? super Key> recipeClickHandler) {
        this.recipeSelectHandler = recipeClickHandler;
    }

}
