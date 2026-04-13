package xyz.xenondevs.invui.internal.menu;

import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTInt;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.recipe.data.MerchantItemCost;
import com.github.retrooper.packetevents.protocol.recipe.data.MerchantOffer;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSelectTrade;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMerchantOffers;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jspecify.annotations.Nullable;
import xyz.xenondevs.invui.internal.network.PacketListener;
import xyz.xenondevs.invui.internal.util.MathUtils;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.util.ItemUtils;
import xyz.xenondevs.invui.window.MerchantWindow;

import java.util.List;
import java.util.function.Consumer;

/**
 * A packet-based merchant menu.
 */
public class CustomMerchantMenu extends CustomContainerMenu {
    private @Nullable Consumer<? super Integer> tradeSelectHandler;
    private @Nullable WrapperPlayServerMerchantOffers initialTradesPacket;

    public CustomMerchantMenu(Player player) {
        super(MenuType.MERCHANT, player);
    }

    @Override
    public void open(Component title) {
        PacketListener.getInstance().redirectIncoming(
            player,
            PacketType.Play.Client.SELECT_TRADE,
            WrapperPlayClientSelectTrade::new,
            incoming
        );
        var initialPacket = initialTradesPacket;
        initialTradesPacket = null;
        super.open(title, initialPacket != null ? List.of(initialPacket) : List.of());
    }

    @Override
    public void handleClosed() {
        handleClosed(InventoryCloseEvent.Reason.UNKNOWN);
    }

    @Override
    public void handleClosed(InventoryCloseEvent.Reason cause) {
        PacketListener.getInstance().removeRedirect(player, PacketType.Play.Client.SELECT_TRADE);
        super.handleClosed(cause);
    }

    @Override
    protected UpdateType processPacket(PacketWrapper<?> packet) {
        if (packet instanceof WrapperPlayClientSelectTrade selectTrade) {
            if (tradeSelectHandler != null)
                tradeSelectHandler.accept(selectTrade.getSlot());
            return UpdateType.NONE;
        }
        return super.processPacket(packet);
    }

    public void sendTrades(
        List<? extends MerchantWindow.Trade> trades,
        int level,
        double progress,
        boolean restockMessage
    ) {
        PacketListener.getInstance().injectOutgoing(player, createTradesPacket(trades, level, progress, restockMessage));
    }

    public void prepareInitialTrades(
        List<? extends MerchantWindow.Trade> trades,
        int level,
        double progress,
        boolean restockMessage
    ) {
        initialTradesPacket = createTradesPacket(trades, level, progress, restockMessage);
    }

    private WrapperPlayServerMerchantOffers createTradesPacket(
        List<? extends MerchantWindow.Trade> trades,
        int level,
        double progress,
        boolean restockMessage
    ) {
        var offers = trades.stream()
            .map(this::toOffer)
            .toList();
        int xp = merchantXp(level, progress);

        return new WrapperPlayServerMerchantOffers(
            containerId,
            offers,
            level,
            xp,
            progress >= 0,
            restockMessage
        );
    }

    private MerchantOffer toOffer(MerchantWindow.Trade trade) {
        MerchantItemCost first = MerchantItemCost.ofItem(toTradeItem(trade.getFirstInput()));
        MerchantItemCost second = trade.getSecondInput() != null
            ? MerchantItemCost.ofItem(toTradeItem(trade.getSecondInput()))
            : null;
        ItemStack output = toTradeItem(trade.getOutput());

        int uses = trade.isAvailable() ? 0 : 1;
        int maxUses = 1;
        int xp = 0;
        int specialPriceDiff = -trade.getDiscount();
        float priceMultiplier = 0f;
        int demand = 0;

        return MerchantOffer.of(first, second, output, uses, maxUses, xp,
            specialPriceDiff, priceMultiplier, demand);
    }

    private ItemStack toTradeItem(@Nullable Item item) {
        var bukkit = item == null
            ? ItemUtils.getPlaceholder()
            : ItemUtils.takeOrPlaceholder(item.getItemProvider(player).get(player.locale()));
        ItemStack peItem = SpigotConversionUtil.fromBukkitItemStack(bukkit).copy();
        int tag = MathUtils.RANDOM.nextInt();

        // Modern: custom_data component
        NBTCompound customData = peItem.getComponent(ComponentTypes.CUSTOM_DATA)
            .orElseGet(NBTCompound::new);
        customData.setTag("invui_merchant", new NBTInt(tag));
        peItem.setComponent(ComponentTypes.CUSTOM_DATA, customData);

        // Legacy: top-level NBT tag (for pre-1.20.5 wire format)
        NBTCompound legacy = peItem.getNBT();
        if (legacy == null) legacy = new NBTCompound();
        legacy.setTag("invui_merchant", new NBTInt(tag));
        peItem.setNBT(legacy);

        return peItem;
    }

    private static int merchantXp(int level, double progress) {
        if (progress < 0 || level <= 0)
            return 0;

        return switch (level) {
            case 1 -> (int) (0 * progress);
            case 2 -> (int) (10 * progress);
            case 3 -> (int) (70 * progress);
            case 4 -> (int) (150 * progress);
            case 5 -> (int) (250 * progress);
            default -> 0;
        };
    }

    public void setTradeSelectHandler(Consumer<? super Integer> tradeSelectHandler) {
        this.tradeSelectHandler = tradeSelectHandler;
    }

}
