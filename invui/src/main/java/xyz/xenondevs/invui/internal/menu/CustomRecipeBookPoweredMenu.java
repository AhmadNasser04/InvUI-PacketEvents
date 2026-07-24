package xyz.xenondevs.invui.internal.menu;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCraftRecipeRequest;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCraftRecipeResponse;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jspecify.annotations.Nullable;
import xyz.xenondevs.invui.internal.network.PacketListener;
import xyz.xenondevs.invui.internal.util.RecipeResolver;

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
    public void handleClosed(InventoryCloseEvent.Reason cause) {
        PacketListener.getInstance().removeRedirect(player, PacketType.Play.Client.CRAFT_RECIPE_REQUEST);
        super.handleClosed(cause);
    }

    @Override
    protected UpdateType processPacket(PacketWrapper<?> packet) {
        if (packet instanceof WrapperPlayClientCraftRecipeRequest request) {
            if (request.getWindowId() != containerId)
                return UpdateType.NONE;

            if (recipeSelectHandler != null) {
                Key key = resolveRecipeKey(request);
                if (key != null)
                    recipeSelectHandler.accept(key);
            }
            return UpdateType.NONE;
        }
        return super.processPacket(packet);
    }

    /**
     * Resolves the recipe the client selected: pre-1.21.2 clicks carry the key directly;
     * 1.21.2+ clicks carry a display id that is translated back through the display data
     * the server previously sent (see {@link RecipeResolver}).
     */
    private @Nullable Key resolveRecipeKey(WrapperPlayClientCraftRecipeRequest request) {
        var resourceLocation = request.getRecipeKey();
        if (resourceLocation != null)
            return Key.key(resourceLocation.getNamespace(), resourceLocation.getKey());

        var displayId = request.getRecipeId();
        if (displayId == null)
            return null;
        var cached = PacketListener.getInstance().getCachedRecipe(player, displayId.getId());
        return cached != null ? RecipeResolver.resolveKey(cached) : null;
    }

    /**
     * Displays a ghost recipe of the given id in the menu. Recipe types without a
     * menu-displayable form (custom {@code Recipe} implementations) are ignored.
     *
     * @param id The recipe id
     */
    public void sendGhostRecipe(Key id) {
        var recipe = Bukkit.getRecipe(new NamespacedKey(id.namespace(), id.value()));
        if (recipe == null)
            return;
        var display = RecipeResolver.toDisplay(recipe);
        if (display == null)
            return;

        PacketListener.getInstance().injectOutgoing(
            player,
            new WrapperPlayServerCraftRecipeResponse(containerId, display)
        );
    }

    public void setRecipeClickHandler(Consumer<? super Key> recipeClickHandler) {
        this.recipeSelectHandler = recipeClickHandler;
    }

}
