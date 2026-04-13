package xyz.xenondevs.invui.internal.menu;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.recipe.display.RecipeDisplay;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCraftRecipeResponse;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
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

    /**
     * Displays a ghost recipe of the given id in the menu.
     *
     * @param id The recipe id
     */
    public void sendGhostRecipe(Key id) {
        ServerVersion version = PacketEvents.getAPI().getServerManager().getVersion();
        if (version.isNewerThanOrEquals(ServerVersion.V_1_21_2)) {
            // Skip silently — 1.21.2+ requires a RecipeDisplay we don't have.
            return;
        }
        var packet = new WrapperPlayServerCraftRecipeResponse(
            containerId,
            new ResourceLocation(id.namespace(), id.value())
        );
        PacketListener.getInstance().injectOutgoing(player, packet);
    }

    /**
     * Sets the handler that is called when a recipe is selected in the recipe book.
     *
     * @param recipeClickHandler The recipe click handler
     */
    public void setRecipeClickHandler(Consumer<? super Key> recipeClickHandler) {
        this.recipeSelectHandler = recipeClickHandler;
    }

}
