package xyz.xenondevs.invui.internal.menu;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow.WindowClickType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindowButton;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSelectBundleItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCursorItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowProperty;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.jspecify.annotations.Nullable;
import xyz.xenondevs.invui.Click;
import xyz.xenondevs.invui.InvUI;
import xyz.xenondevs.invui.internal.network.PacketListener;
import xyz.xenondevs.invui.internal.util.FakeInventoryView;
import xyz.xenondevs.invui.internal.util.MathUtils;
import xyz.xenondevs.invui.window.Window;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A packet-based container menu.
 */
public abstract class CustomContainerMenu {

    /** Number of slots in the lower (player) inventory portion of a container. */
    private static final int LOWER_INVENTORY_SIZE = 36;

    /** Timeout for entries in {@link #pendingPongs}, in milliseconds. */
    private static final long PING_TIMEOUT_MS = 10_000;

    /** Shared vanilla-style container-id counter: 1..100 cyclic, avoiding 0 (the player inventory). */
    private static final AtomicInteger CONTAINER_ID_COUNTER = new AtomicInteger(0);

    private static int nextContainerId() {
        int id = CONTAINER_ID_COUNTER.updateAndGet(current -> (current % 100) + 1);
        return id;
    }

    protected final MenuType menuType;
    protected final int containerId;
    protected final Player player;
    private final ServerPlayer serverPlayer;
    private final ContainerMenuProxy proxy;
    private @Nullable Window window;

    /**
     * Authoritative upper+lower slot contents, in Bukkit form. The
     * {@link SpigotConversionUtil#fromBukkitItemStack} conversion to a
     * PacketEvents {@code ItemStack} is deferred to the packet sender worker
     * thread and only happens for slots that are actually being sent, so the
     * caller's sync thread never pays that cost.
     */
    private final org.bukkit.inventory.@Nullable ItemStack[] bukkitItems;
    /** Cursor contents while this menu is active. */
    private org.bukkit.inventory.@Nullable ItemStack carried;
    /**
     * Last value sent to the client for each slot. Combined with
     * {@link #dirtySlots} to decide whether a slot needs resending: a slot is
     * considered dirty if its bit is set <em>or</em> its current
     * {@link #bukkitItems} entry differs from the last-sent entry.
     */
    private final org.bukkit.inventory.@Nullable ItemStack[] lastSentItems;
    /** Bit set for each slot index that must be resent regardless of content equality. */
    private final BitSet dirtySlots;

    private org.bukkit.inventory.@Nullable ItemStack lastSentCarried;
    /** Force-resend flag for the cursor, paralleling {@link #dirtySlots}. */
    private boolean carriedDirty = true;
    private Function<? super ItemStack, ? extends ItemStack> cursorVisualizer = Function.identity();

    protected final int[] dataSlots;
    protected final int[] remoteDataSlots;
    private int stateId;
    private Component title = Component.empty();

    private final IntSet dragSlots = new IntLinkedOpenHashSet();
    private ClickType dragMode = ClickType.LEFT;

    protected final Queue<PacketWrapper<?>> incoming = new ConcurrentLinkedQueue<>();
    private final Map<Integer, PingData> pendingPongs = new ConcurrentHashMap<>();

    /**
     * Creates a new {@link CustomContainerMenu} for the specified player.
     *
     * @param menuType the type of the menu
     * @param player   the player that will see the menu
     */
    protected CustomContainerMenu(MenuType menuType, Player player) {
        this.menuType = menuType;
        this.player = player;
        this.serverPlayer = ((CraftPlayer) player).getHandle();
        this.containerId = nextContainerId();

        int size = menuType.size() + LOWER_INVENTORY_SIZE;
        this.bukkitItems = new org.bukkit.inventory.ItemStack[size];
        this.lastSentItems = new org.bukkit.inventory.ItemStack[size];
        this.dirtySlots = new BitSet(size);
        this.dirtySlots.set(0, size);
        loadPlayerInventoryIntoLowerSlots();
        this.carried = copyBukkitItem(player.getItemOnCursor());

        int dataSize = menuType.dataSlotCount();
        this.dataSlots = new int[dataSize];
        this.remoteDataSlots = new int[dataSize];
        Arrays.fill(this.remoteDataSlots, Integer.MIN_VALUE); // force initial send

        this.proxy = new ContainerMenuProxy();
    }

    private void loadPlayerInventoryIntoLowerSlots() {
        int upper = menuType.size();
        var inv = player.getInventory();
        // Main inventory rows 1-3 (Bukkit slots 9..35) -> our slots upper+0..upper+26
        for (int i = 0; i < 27; i++) {
            bukkitItems[upper + i] = copyBukkitItem(inv.getItem(9 + i));
        }
        // Hotbar (Bukkit slots 0..8) -> our slots upper+27..upper+35
        for (int i = 0; i < 9; i++) {
            bukkitItems[upper + 27 + i] = copyBukkitItem(inv.getItem(i));
        }
    }

    //<editor-fold desc="slot / cursor accessors">

    /**
     * Puts a Bukkit item stack into the specified slot. Only a cheap bukkit
     * clone happens here — the PacketEvents conversion is deferred to the
     * sender worker thread in {@link #sendChangesToRemote(int)}.
     */
    public void setItem(int slot, org.bukkit.inventory.@Nullable ItemStack item) {
        if (slot < 0 || slot >= bukkitItems.length)
            throw new IllegalArgumentException("Slot out of bounds: " + slot);
        bukkitItems[slot] = copyBukkitItem(item);
    }

    public void setItemOwned(int slot, org.bukkit.inventory.@Nullable ItemStack item) {
        if (slot < 0 || slot >= bukkitItems.length)
            throw new IllegalArgumentException("Slot out of bounds: " + slot);
        if (item != null && item.getType().isAir())
            item = null;
        bukkitItems[slot] = item;
    }

    /**
     * Returns a Bukkit clone of the item currently in the slot.
     */
    public org.bukkit.inventory.@Nullable ItemStack getItem(int slot) {
        if (slot < 0 || slot >= bukkitItems.length)
            throw new IllegalArgumentException("Slot out of bounds: " + slot);
        return copyBukkitItem(bukkitItems[slot]);
    }

    public void setCursor(org.bukkit.inventory.@Nullable ItemStack item) {
        carried = copyBukkitItem(item);
        if (serverPlayer.containerMenu == serverPlayer.inventoryMenu) {
            player.setItemOnCursor(copyBukkitItem(carried));
        }
        carriedDirty = true;
    }

    public org.bukkit.inventory.@Nullable ItemStack getCursor() {
        if (serverPlayer.containerMenu == serverPlayer.inventoryMenu) {
            carried = copyBukkitItem(player.getItemOnCursor());
        }
        return copyBukkitItem(carried);
    }

    public void setCursorVisualizer(Function<? super ItemStack, ? extends ItemStack> cursorVisualizer) {
        this.cursorVisualizer = cursorVisualizer;
        carriedDirty = true;
    }

    private static ItemStack fromBukkit(org.bukkit.inventory.@Nullable ItemStack item) {
        if (item == null || item.getType().isAir())
            return ItemStack.EMPTY;
        return SpigotConversionUtil.fromBukkitItemStack(item);
    }

    private static org.bukkit.inventory.@Nullable ItemStack copyBukkitItem(org.bukkit.inventory.@Nullable ItemStack item) {
        if (item == null || item.getType().isAir())
            return null;
        return item.clone();
    }

    //</editor-fold>

    //<editor-fold desc="synchronization">
    public void markSlotDirty(int slot) {
        if (slot < 0 || slot >= bukkitItems.length)
            throw new IllegalArgumentException("Slot out of bounds: " + slot);
        dirtySlots.set(slot);
    }

    public void sendChangesToRemote(int pingId) {
        int size = bukkitItems.length;
        int[] slotStateIds = new int[size];
        int[] slotIndices = new int[size];
        org.bukkit.inventory.@Nullable ItemStack[] slotSnapshots = new org.bukkit.inventory.ItemStack[size];
        int dirtyCount = 0;

        for (int i = 0; i < size; i++) {
            var current = bukkitItems[i];
            if (!dirtySlots.get(i) && Objects.equals(lastSentItems[i], current))
                continue;
            slotStateIds[dirtyCount] = incrementStateId();
            slotIndices[dirtyCount] = i;
            slotSnapshots[dirtyCount] = current;
            lastSentItems[i] = current;
            dirtyCount++;
        }
        dirtySlots.clear();

        boolean sendCarried = carriedDirty || !Objects.equals(lastSentCarried, carried);
        org.bukkit.inventory.@Nullable ItemStack carriedSnapshot = carried;
        if (sendCarried) {
            lastSentCarried = carried;
            carriedDirty = false;
        }

        int dataCount = 0;
        int[] changedDataIndices = new int[dataSlots.length];
        int[] changedDataValues = new int[dataSlots.length];
        for (int i = 0; i < dataSlots.length; i++) {
            if (dataSlots[i] != remoteDataSlots[i]) {
                changedDataIndices[dataCount] = i;
                changedDataValues[dataCount] = dataSlots[i];
                remoteDataSlots[i] = dataSlots[i];
                dataCount++;
            }
        }

        @Nullable PacketWrapper<?> pingPacket = pingId >= 0 ? createMaskedPingPacket(pingId) : null;

        if (dirtyCount == 0 && !sendCarried && dataCount == 0 && pingPacket == null)
            return;

        int finalDirtyCount = dirtyCount;
        int finalDataCount = dataCount;
        boolean finalSendCarried = sendCarried;
        int cId = containerId;

        PacketListener.getInstance().injectOutgoing(player, () -> {
            var packets = new ArrayList<PacketWrapper<?>>(finalDirtyCount + finalDataCount + 2);
            for (int i = 0; i < finalDirtyCount; i++) {
                packets.add(new WrapperPlayServerSetSlot(
                    cId, slotStateIds[i], slotIndices[i], fromBukkit(slotSnapshots[i])
                ));
            }
            if (finalSendCarried) {
                packets.add(new WrapperPlayServerSetCursorItem(visualizeCarried(carriedSnapshot)));
            }
            for (int i = 0; i < finalDataCount; i++) {
                packets.add(new WrapperPlayServerWindowProperty(cId, changedDataIndices[i], changedDataValues[i]));
            }
            if (pingPacket != null)
                packets.add(pingPacket);
            return packets;
        });
    }

    /**
     * Sends a full-state snapshot to the client.
     */
    public void sendAllToRemote(int pingId) {
        dispatchInitPackets(null, List.of(), pingId);
        markRemoteSynced();
    }

    /**
     * Opens the menu on the client with the given title.
     */
    public void sendOpenPacket(Component title) {
        sendOpenPacket(title, List.of());
    }

    protected void sendOpenPacket(Component title, List<? extends PacketWrapper<?>> extraInitPackets) {
        this.title = title;

        int typeId = menuType.idFor(PacketEvents.getAPI().getServerManager().getVersion());
        dispatchInitPackets(new WrapperPlayServerOpenWindow(containerId, typeId, title), extraInitPackets, -1);
        markRemoteSynced();
    }

    private void dispatchInitPackets(
        @Nullable PacketWrapper<?> prefix,
        List<? extends PacketWrapper<?>> extraInitPackets,
        int pingId
    ) {
        int cId = containerId;
        org.bukkit.inventory.@Nullable ItemStack carriedSnapshot = carried;
        int[] dataSnapshot = dataSlots.clone();
        @Nullable PacketWrapper<?> pingPacket = pingId >= 0 ? createMaskedPingPacket(pingId) : null;
        var extraSnapshot = List.copyOf(extraInitPackets);

        if (shouldUseSparseInit()) {
            // Sparse path: slot selection needs player.getInventory(), which
            // must be read on the sync thread. We snapshot the selected slots'
            // Bukkit stacks here and convert them on the worker.
            int size = bukkitItems.length;
            int[] sparseStateIds = new int[size];
            int[] sparseSlots = new int[size];
            org.bukkit.inventory.@Nullable ItemStack[] sparseBukkit = new org.bukkit.inventory.ItemStack[size];
            int sparseCount = 0;

            int upper = menuType.size();
            for (int slot = 0; slot < upper; slot++) {
                var current = bukkitItems[slot];
                if (current != null) {
                    sparseStateIds[sparseCount] = incrementStateId();
                    sparseSlots[sparseCount] = slot;
                    sparseBukkit[sparseCount] = current;
                    sparseCount++;
                }
            }
            for (int lower = 0; lower < LOWER_INVENTORY_SIZE; lower++) {
                int slot = upper + lower;
                var current = bukkitItems[slot];
                if (!Objects.equals(current, getPlayerInventoryBaselineItem(lower))) {
                    sparseStateIds[sparseCount] = incrementStateId();
                    sparseSlots[sparseCount] = slot;
                    sparseBukkit[sparseCount] = current;
                    sparseCount++;
                }
            }

            int finalSparseCount = sparseCount;
            PacketListener.getInstance().injectOutgoing(player, () -> {
                var packets = new ArrayList<PacketWrapper<?>>(finalSparseCount + dataSnapshot.length + extraSnapshot.size() + 3);
                if (prefix != null)
                    packets.add(prefix);
                for (int i = 0; i < finalSparseCount; i++) {
                    packets.add(new WrapperPlayServerSetSlot(
                        cId, sparseStateIds[i], sparseSlots[i], fromBukkit(sparseBukkit[i])
                    ));
                }
                packets.add(new WrapperPlayServerSetCursorItem(visualizeCarried(carriedSnapshot)));
                for (int i = 0; i < dataSnapshot.length; i++) {
                    packets.add(new WrapperPlayServerWindowProperty(cId, i, dataSnapshot[i]));
                }
                packets.addAll(extraSnapshot);
                if (pingPacket != null)
                    packets.add(pingPacket);
                return packets;
            });
        } else {
            org.bukkit.inventory.@Nullable ItemStack[] bukkitSnapshot = bukkitItems.clone();
            int windowItemsStateId = incrementStateId();

            PacketListener.getInstance().injectOutgoing(player, () -> {
                var packets = new ArrayList<PacketWrapper<?>>(dataSnapshot.length + extraSnapshot.size() + 4);
                if (prefix != null)
                    packets.add(prefix);
                var contents = new ArrayList<ItemStack>(bukkitSnapshot.length);
                for (var b : bukkitSnapshot)
                    contents.add(fromBukkit(b));
                ItemStack peCarried = visualizeCarried(carriedSnapshot);
                packets.add(new WrapperPlayServerWindowItems(cId, windowItemsStateId, contents, peCarried));
                packets.add(new WrapperPlayServerSetCursorItem(peCarried));
                for (int i = 0; i < dataSnapshot.length; i++) {
                    packets.add(new WrapperPlayServerWindowProperty(cId, i, dataSnapshot[i]));
                }
                packets.addAll(extraSnapshot);
                if (pingPacket != null)
                    packets.add(pingPacket);
                return packets;
            });
        }
    }

    private boolean shouldUseSparseInit() {
        if (!isSparseInitSupported())
            return false;

        int changedSlots = 0;
        int upper = menuType.size();
        for (int slot = 0; slot < upper; slot++) {
            if (bukkitItems[slot] != null) {
                changedSlots++;
            }
        }

        for (int lower = 0; lower < LOWER_INVENTORY_SIZE; lower++) {
            if (!Objects.equals(bukkitItems[upper + lower], getPlayerInventoryBaselineItem(lower))) {
                changedSlots++;
            }
        }

        if (carried != null) {
            changedSlots++;
        }

        return changedSlots <= 16;
    }

    private boolean isSparseInitSupported() {
        return switch (menuType) {
            case GENERIC_9x1, GENERIC_9x2, GENERIC_9x3, GENERIC_9x4, GENERIC_9x5, GENERIC_9x6, GENERIC_3x3, HOPPER -> true;
            default -> false;
        };
    }

    private org.bukkit.inventory.@Nullable ItemStack getPlayerInventoryBaselineItem(int lowerSlot) {
        var inv = player.getInventory();
        if (lowerSlot < 27) {
            return copyBukkitItem(inv.getItem(9 + lowerSlot));
        }
        return copyBukkitItem(inv.getItem(lowerSlot - 27));
    }

    private ItemStack visualizeCarried(org.bukkit.inventory.@Nullable ItemStack item) {
        return cursorVisualizer.apply(fromBukkit(item));
    }

    private void markRemoteSynced() {
        System.arraycopy(bukkitItems, 0, lastSentItems, 0, bukkitItems.length);
        dirtySlots.clear();
        lastSentCarried = carried;
        carriedDirty = false;
        System.arraycopy(dataSlots, 0, remoteDataSlots, 0, dataSlots.length);
    }

    //</editor-fold>

    //<editor-fold desc="lifecycle">

    /**
     * Registers the PacketEvents listeners, pins the NMS proxy menu onto
     * the player, and sends the open-window sequence.
     */
    public void open(Component title) {
        open(title, List.of());
    }

    /**
     * Opens this menu and appends any extra menu-specific packets that must be
     * serialized in the same send batch as the initial open sequence.
     */
    protected void open(Component title, List<? extends PacketWrapper<?>> extraInitPackets) {
        var pl = PacketListener.getInstance();
        pl.redirectIncoming(player, PacketType.Play.Client.CLICK_WINDOW_BUTTON,
            WrapperPlayClientClickWindowButton::new, incoming);
        pl.redirectIncoming(player, PacketType.Play.Client.CLICK_WINDOW,
            WrapperPlayClientClickWindow::new, incoming);
        pl.redirectIncoming(player, PacketType.Play.Client.CLOSE_WINDOW,
            WrapperPlayClientCloseWindow::new, incoming);
        pl.redirectIncoming(player, PacketType.Play.Client.SELECT_BUNDLE_ITEM,
            WrapperPlayClientSelectBundleItem::new, incoming);
        pl.listenIncoming(player, PacketType.Play.Client.PONG,
            WrapperPlayClientPong::new, incoming);

        pl.discard(player, PacketType.Play.Server.OPEN_WINDOW);
        pl.discard(player, PacketType.Play.Server.WINDOW_ITEMS);
        pl.discard(player, PacketType.Play.Server.WINDOW_PROPERTY);
        pl.discard(player, PacketType.Play.Server.SET_SLOT);
        pl.discard(player, PacketType.Play.Server.SET_CURSOR_ITEM);

        serverPlayer.containerMenu = proxy;
        sendOpenPacket(title, extraInitPackets);
    }

    /**
     * Runs cleanup logic after this menu has been dismissed.
     */
    public void handleClosed() {
        handleClosed(InventoryCloseEvent.Reason.UNKNOWN);
    }

    /**
     * Runs cleanup logic after this menu has been dismissed.
     */
    public void handleClosed(InventoryCloseEvent.Reason cause) {
        var pl = PacketListener.getInstance();
        pl.removeRedirect(player, PacketType.Play.Client.CLICK_WINDOW_BUTTON);
        pl.removeRedirect(player, PacketType.Play.Client.CLICK_WINDOW);
        pl.removeRedirect(player, PacketType.Play.Client.CLOSE_WINDOW);
        pl.removeRedirect(player, PacketType.Play.Client.SELECT_BUNDLE_ITEM);
        pl.stopListening(player, PacketType.Play.Client.PONG);

        pl.stopDiscard(player, PacketType.Play.Server.OPEN_WINDOW);
        pl.stopDiscard(player, PacketType.Play.Server.WINDOW_ITEMS);
        pl.stopDiscard(player, PacketType.Play.Server.WINDOW_PROPERTY);
        pl.stopDiscard(player, PacketType.Play.Server.SET_SLOT);
        pl.stopDiscard(player, PacketType.Play.Server.SET_CURSOR_ITEM);

        // Hand the player back to vanilla's inventory menu and ask vanilla
        // to resync the player inventory view. We overwrote the lower-inventory
        // visuals with items[] while the menu was open, so the client needs
        // fresh SetSlot packets for slots 36..44 of the vanilla inventory menu.
        if (serverPlayer.containerMenu == proxy) {
            serverPlayer.containerMenu = serverPlayer.inventoryMenu;
            player.setItemOnCursor(copyBukkitItem(carried));
        }
        if (cause != InventoryCloseEvent.Reason.OPEN_NEW) {
            player.updateInventory();
        }
    }

    /**
     * Tells the client to close this menu without routing through Bukkit's
     * {@code player.closeInventory()}, which Paper requires to run
     * synchronously because it fires {@link InventoryCloseEvent}.
     */
    public void closeForViewer() {
        PacketListener.getInstance().injectOutgoing(player, new WrapperPlayServerCloseWindow(containerId));
    }

    private PacketWrapper<?> createMaskedPingPacket(int id) {
        int ping = MathUtils.RANDOM.nextInt();
        pendingPongs.put(ping, new PingData(id, System.currentTimeMillis()));

        long now = System.currentTimeMillis();
        pendingPongs.values().removeIf(data -> now - data.timestamp() > PING_TIMEOUT_MS);

        return new WrapperPlayServerPing(ping);
    }

    //</editor-fold>

    //<editor-fold desc="packet processing">

    /** Drains the incoming queue. */
    public UpdateType processIncoming() {
        PacketWrapper<?> packet;
        UpdateType updateType = UpdateType.NONE;
        while ((packet = incoming.poll()) != null) {
            updateType = updateType.or(processPacket(packet));
        }
        return updateType;
    }

    /**
     * Processes a single queued packet. Subclasses override to handle their
     * specialized packets and delegate here for the generic ones.
     */
    protected UpdateType processPacket(PacketWrapper<?> packet) {
        if (packet instanceof WrapperPlayClientClickWindowButton btn) {
            if (btn.getWindowId() != containerId)
                return UpdateType.NONE;
            return handleButtonClick(btn.getButtonId());
        }
        if (packet instanceof WrapperPlayClientClickWindow click) {
            if (click.getWindowId() != containerId)
                return UpdateType.NONE;
            return handleClick(click);
        }
        if (packet instanceof WrapperPlayClientSelectBundleItem bundle) {
            return handleBundleSelect(bundle);
        }
        if (packet instanceof WrapperPlayClientCloseWindow close) {
            handleClose(close);
            return UpdateType.NONE;
        }
        if (packet instanceof WrapperPlayClientPong pong) {
            handlePong(pong);
            return UpdateType.NONE;
        }
        throw new UnsupportedOperationException("Unknown packet wrapper: " + packet.getClass().getName());
    }

    private void handlePong(WrapperPlayClientPong packet) {
        var data = pendingPongs.remove(packet.getId());
        if (data != null)
            getWindowEvents().handlePong(data.id());
    }

    private void handleClose(WrapperPlayClientCloseWindow packet) {
        if (packet.getWindowId() != containerId)
            return;

        if (getWindow().isCloseable()) {
            // Client-initiated close: drive the InvUI close path directly,
            // matching upstream. We bypass Bukkit's event machinery because
            // the client's CLOSE_WINDOW packet was cancelled at the packet
            // layer, so vanilla never sees it.
            getWindowEvents().handleClose(InventoryCloseEvent.Reason.PLAYER);
            if (serverPlayer.containerMenu == proxy) {
                serverPlayer.containerMenu = serverPlayer.inventoryMenu;
            }
        } else {
            sendOpenPacket(title);
        }
    }

    /**
     * Handles a CLICK_WINDOW packet. The server is authoritative — we
     * ignore the client-reported slot deltas (legacy or hashed), dispatch
     * the interaction to the window, then dirty every slot so the next
     * sync corrects any optimistic client prediction.
     */
    protected UpdateType handleClick(WrapperPlayClientClickWindow packet) {
        boolean stateIdMismatch = packet.getStateId().map(id -> id != stateId).orElse(false);

        carriedDirty = true;

        if (packet.getWindowClickType() == WindowClickType.QUICK_CRAFT) {
            if (!handleDragClick(packet))
                return stateIdMismatch ? UpdateType.FULL : UpdateType.DIRTY;
        } else {
            handleNormalClick(packet);
        }

        // Dirty every slot, upper and lower. Shift-clicks, swaps, drags and
        // double-click-collect can touch either half, and since we cancel the
        // click at the packet layer the server state is unchanged from the
        // client's perspective. We do NOT re-read the player inventory here —
        // that would clobber custom items the lower gui placed in pass-through
        // slots. bukkitItems[] is already authoritative; AbstractWindow keeps
        // it current through its gui element observer chain.
        dirtySlots.set(0, bukkitItems.length);

        return stateIdMismatch ? UpdateType.FULL : UpdateType.DIRTY;
    }

    private void handleNormalClick(WrapperPlayClientClickWindow packet) {
        int hotbarBtn = -1;
        int button = packet.getButton();
        int slot = packet.getSlot();
        ClickType clickType = switch (packet.getWindowClickType()) {
            case PICKUP -> switch (button) {
                case 0 -> ClickType.LEFT;
                case 1 -> ClickType.RIGHT;
                default -> ClickType.UNKNOWN;
            };
            case QUICK_MOVE -> switch (button) {
                case 0 -> ClickType.SHIFT_LEFT;
                case 1 -> ClickType.SHIFT_RIGHT;
                default -> ClickType.UNKNOWN;
            };
            case SWAP -> {
                if (button >= 0 && button <= 8) {
                    hotbarBtn = button;
                    yield ClickType.NUMBER_KEY;
                }
                if (button == 40) {
                    // off-hand swap — force a player-inventory resync so the
                    // client's optimistic off-hand update gets corrected.
                    player.updateInventory();
                    yield ClickType.SWAP_OFFHAND;
                }
                yield ClickType.UNKNOWN;
            }
            case CLONE -> ClickType.MIDDLE;
            case THROW -> switch (button) {
                case 0 -> slot > 0 ? ClickType.DROP : ClickType.LEFT;
                case 1 -> slot > 0 ? ClickType.CONTROL_DROP : ClickType.RIGHT;
                default -> ClickType.UNKNOWN;
            };
            case PICKUP_ALL -> ClickType.DOUBLE_CLICK;
            case QUICK_CRAFT -> throw new AssertionError("unreachable");
            case UNKNOWN -> ClickType.UNKNOWN;
        };

        int finalHotbarBtn = hotbarBtn;
        runInInteractionContext(() -> {
            Click click = new Click(player, clickType, finalHotbarBtn);
            getWindowEvents().handleClick(slot, click);
        });
    }

    private boolean handleDragClick(WrapperPlayClientClickWindow packet) {
        int button = packet.getButton();
        int slot = packet.getSlot();
        switch (button) {
            // add slot for left, right, middle drag
            case 1, 5, 9 -> {
                if (slot >= 0 && slot < bukkitItems.length) {
                    dragSlots.add(slot);
                }
                dragMode = switch (button) {
                    case 1 -> ClickType.LEFT;
                    case 5 -> ClickType.RIGHT;
                    case 9 -> ClickType.MIDDLE;
                    default -> throw new AssertionError();
                };
                return false;
            }
            // end left, right, middle drag
            case 2, 6, 10 -> {
                runInInteractionContext(() -> {
                    if (dragSlots.size() == 1) {
                        int oneSlot = dragSlots.iterator().nextInt();
                        getWindowEvents().handleClick(oneSlot, new Click(player, dragMode, -1));
                    } else {
                        getWindowEvents().handleDrag(dragSlots, dragMode);
                    }
                });
                dragSlots.clear();
            }
            // drag start can be ignored
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Handles a bundle item selection. Dispatches the selection to the
     * window handler and dirties the slot so the next sync resends the
     * bundle with whatever state the handler leaves it in.
     */
    private UpdateType handleBundleSelect(WrapperPlayClientSelectBundleItem packet) {
        int slot = packet.getSlotId();
        if (slot < 0 || slot >= bukkitItems.length)
            return UpdateType.NONE;

        int selected = packet.getSelectedItemIndex();
        markSlotDirty(slot);

        runInInteractionContext(() -> getWindowEvents().handleBundleSelect(slot, selected));
        return UpdateType.DIRTY;
    }

    /** No-op default; subclasses override for menu button clicks. */
    protected UpdateType handleButtonClick(int buttonId) {
        return UpdateType.NONE;
    }

    protected void runInInteractionContext(Runnable run) {
        try {
            run.run();
        } catch (Throwable t) {
            InvUI.getInstance().handleException("An exception occurred while handling a window interaction", t);
        }
    }

    //</editor-fold>

    /** Increments and returns the current window state id, wrapped at 32767. */
    public int incrementStateId() {
        this.stateId = (this.stateId + 1) & 32767;
        return this.stateId;
    }

    public void setWindow(Window window) {
        this.window = window;
    }

    public Window getWindow() {
        if (window == null)
            throw new IllegalStateException("Window is not set");
        return window;
    }

    public WindowEventListener getWindowEvents() {
        if (window == null)
            throw new IllegalStateException("Window is not set");
        return (WindowEventListener) window;
    }

    /**
     * Pending-ping bookkeeping: maps a randomly generated outgoing ping id to
     * the window state id it corresponds to and the time it was sent.
     */
    private record PingData(int id, long timestamp) {}

    /**
     * NMS {@link AbstractContainerMenu} we pin onto {@code serverPlayer.containerMenu}
     * while the InvUI window is open. Its only job is to make vanilla think
     * a container is open — all broadcast methods are no-ops because we
     * sync state ourselves via PacketEvents wrappers. Cursor access is
     * routed through the Bukkit player so it stays in sync with
     * {@code AbstractGui}'s click handling.
     */
    private final class ContainerMenuProxy extends AbstractContainerMenu {

        private final Inventory bukkitInventory = new CraftInventory(new SimpleContainer(0));

        ContainerMenuProxy() {
            super(CustomContainerMenu.this.menuType.toNms(), CustomContainerMenu.this.containerId);
        }

        @Override
        public net.minecraft.world.item.ItemStack getCarried() {
            return CraftItemStack.asNMSCopy(carried);
        }

        @Override
        public void setCarried(net.minecraft.world.item.ItemStack stack) {
            carried = copyBukkitItem(CraftItemStack.asBukkitCopy(stack));
            carriedDirty = true;
        }

        @Override
        public void broadcastCarriedItem() {
            // handled via our own sync
        }

        @Override
        public void broadcastChanges() {
            // handled via our own sync
        }

        @Override
        public void broadcastFullState() {
            // handled via our own sync
        }

        @Override
        public InventoryView getBukkitView() {
            return new FakeInventoryView(player, bukkitInventory);
        }

        @Override
        public net.minecraft.world.item.ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int i) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(net.minecraft.world.entity.player.Player player) {
            return true;
        }
    }

}
