package xyz.xenondevs.invui.internal.network;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.recipe.RecipePropertySet;
import com.github.retrooper.packetevents.protocol.recipe.SingleInputOptionDisplay;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDeclareRecipes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRecipeBookAdd;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRecipeBookRemove;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import xyz.xenondevs.invui.InvUI;
import xyz.xenondevs.invui.internal.util.RecipeResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class PacketListener implements Listener {

    private static @Nullable PacketListener instance;

    private final ExecutorService sendPool = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
        new SendThreadFactory()
    );
    private final Dispatcher dispatcher = new Dispatcher();
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();

    /**
     * The most recent recipe data (item property sets + stonecutter recipes) vanilla has sent
     * to any client. Recipe content is global, so one snapshot serves all players. Used to
     * merge real item properties into custom stonecutter declarations and to restore the
     * client's recipe state after a custom stonecutter menu closes.
     */
    private volatile @Nullable VanillaRecipeData vanillaRecipeData;

    /**
     * Per-player recipe book contents as sent by vanilla, keyed by recipe display id.
     * On 1.21.2+ the client only ever refers to recipes by these ids, so this cache is
     * the only way to translate recipe-book clicks back to recipe keys.
     */
    private final Map<UUID, Map<Integer, RecipeResolver.CachedRecipe>> recipeBooks = new ConcurrentHashMap<>();

    private PacketListener() {
        PacketEvents.getAPI().getEventManager().registerListener(dispatcher);
        Bukkit.getPluginManager().registerEvents(this, InvUI.getInstance().getPlugin());
        Bukkit.getOnlinePlayers().forEach(player -> states.put(player.getUniqueId(), new PlayerState(sendPool)));
        InvUI.getInstance().addDisableHandler(this::shutdown);
    }

    public static synchronized PacketListener getInstance() {
        PacketListener inst = instance;
        if (inst == null) {
            inst = new PacketListener();
            instance = inst;
        }
        return inst;
    }

    private static synchronized void clearInstance() {
        instance = null;
    }

    private void shutdown() {
        // must be unregistered explicitly: when PacketEvents is owned by another plugin,
        // it outlives InvUI's plugin and would otherwise keep dispatching into it
        PacketEvents.getAPI().getEventManager().unregisterListener(dispatcher);
        states.clear();
        recipeBooks.clear();
        sendPool.shutdown();
        try {
            sendPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        clearInstance();
    }

    public void discard(Player player, PacketTypeCommon type) {
        getState(player.getUniqueId()).discards.add(type);
    }

    public void stopDiscard(Player player, PacketTypeCommon type) {
        getState(player.getUniqueId()).discards.remove(type);
    }

    /**
     * Registers an outgoing filter for the given packet type. The filter runs on the netty
     * thread; returning {@code true} cancels the packet. Unlike {@link #discard}, the filter
     * may inspect (and re-emit parts of) the packet before deciding.
     */
    public void filterOutgoing(Player player, PacketTypeCommon type, Predicate<PacketSendEvent> filter) {
        getState(player.getUniqueId()).filters.put(type, filter);
    }

    public void removeOutgoingFilter(Player player, PacketTypeCommon type) {
        getState(player.getUniqueId()).filters.remove(type);
    }

    public void injectOutgoing(Player player, Supplier<? extends List<? extends PacketWrapper<?>>> wrapperSupplier) {
        PlayerState state = states.get(player.getUniqueId());
        if (state == null) return;

        state.sender.execute(() -> {
            List<? extends PacketWrapper<?>> built;
            try {
                built = wrapperSupplier.get();
            } catch (Throwable t) {
                InvUI.getInstance().handleException("Failed to build packet bundle asynchronously", t);
                return;
            }
            if (built == null || built.isEmpty()) return;
            sendBundle(player, new ArrayList<>(built));
        });
    }

    /** Sends a single wrapper, bypassing this listener's own send rules. */
    public void injectOutgoing(Player player, PacketWrapper<?> wrapper) {
        PlayerState state = states.get(player.getUniqueId());
        if (state == null) return;

        state.sender.execute(() -> {
            try {
                PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, wrapper);
            } catch (Throwable t) {
                InvUI.getInstance().handleException("Failed to send packet asynchronously", t);
            }
        });
    }

    /**
     * Runs {@code action} on the player's sender worker after every send queued so far has
     * completed, establishing ordering between InvUI's async packet queue and follow-up work.
     */
    public void runAfterPendingSends(Player player, Runnable action) {
        PlayerState state = states.get(player.getUniqueId());
        if (state == null) {
            action.run();
            return;
        }
        state.sender.execute(action);
    }

    public @Nullable VanillaRecipeData getVanillaRecipeData() {
        return vanillaRecipeData;
    }

    public RecipeResolver.@Nullable CachedRecipe getCachedRecipe(Player player, int displayId) {
        var book = recipeBooks.get(player.getUniqueId());
        return book != null ? book.get(displayId) : null;
    }

    private static void sendBundle(Player player, List<PacketWrapper<?>> wrappers) {
        try {
            var api = PacketEvents.getAPI();
            var playerManager = api.getPlayerManager();
            var protocolManager = api.getProtocolManager();
            var channel = playerManager.getChannel(player);
            if (channel == null) return;

            var buffers = new ArrayList<>();
            Collections.addAll(buffers, protocolManager.transformWrappers(new WrapperPlayServerBundle(), channel, true));
            for (PacketWrapper<?> wrapper : wrappers) {
                Collections.addAll(buffers, protocolManager.transformWrappers(wrapper, channel, true));
            }
            Collections.addAll(buffers, protocolManager.transformWrappers(new WrapperPlayServerBundle(), channel, true));
            protocolManager.sendPacketsSilently(channel, buffers.toArray());
        } catch (Throwable t) {
            InvUI.getInstance().handleException("Failed to send packet bundle asynchronously", t);
        }
    }

    public <T extends PacketWrapper<?>> void redirectIncoming(
        Player player,
        PacketTypeCommon type,
        Function<PacketReceiveEvent, T> factory,
        Queue<? super T> queue
    ) {
        getState(player.getUniqueId()).redirects.put(type, new Entry<>(factory, queue));
    }

    public boolean removeRedirect(Player player, PacketTypeCommon type) {
        return getState(player.getUniqueId()).redirects.remove(type) != null;
    }

    public <T extends PacketWrapper<?>> void listenIncoming(
        Player player,
        PacketTypeCommon type,
        Function<PacketReceiveEvent, T> factory,
        Queue<? super T> queue
    ) {
        getState(player.getUniqueId()).listeners.put(type, new Entry<>(factory, queue));
    }

    public boolean stopListening(Player player, PacketTypeCommon type) {
        return getState(player.getUniqueId()).listeners.remove(type) != null;
    }

    private PlayerState getState(UUID uuid) {
        var state = states.get(uuid);
        if (state == null)
            throw new IllegalStateException("No packet handler is registered for this player");
        return state;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void handleJoin(PlayerJoinEvent event) {
        states.put(event.getPlayer().getUniqueId(), new PlayerState(sendPool));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private void handleQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
        recipeBooks.remove(event.getPlayer().getUniqueId());
    }

    private void cacheDeclareRecipes(PacketSendEvent event) {
        try {
            var wrapper = new WrapperPlayServerDeclareRecipes(event);
            vanillaRecipeData = new VanillaRecipeData(
                Map.copyOf(wrapper.getItemSets()),
                List.copyOf(wrapper.getStonecutterRecipes())
            );
            // a fresh recipe declaration implies the server's recipes may have been reloaded
            RecipeResolver.invalidateIndex();
        } catch (Throwable t) {
            InvUI.getInstance().handleException("Failed to cache vanilla recipe data", t);
        }
    }

    private void cacheRecipeBookAdd(PacketSendEvent event) {
        try {
            var wrapper = new WrapperPlayServerRecipeBookAdd(event);
            var book = recipeBooks.computeIfAbsent(event.getUser().getUUID(), uuid -> new ConcurrentHashMap<>());
            if (wrapper.isReplace())
                book.clear();
            for (var entry : wrapper.getEntries()) {
                var contents = entry.getContents();
                book.put(contents.getId().getId(), RecipeResolver.fromDisplayEntry(contents));
            }
        } catch (Throwable t) {
            InvUI.getInstance().handleException("Failed to cache recipe book entries", t);
        }
    }

    private void cacheRecipeBookRemove(PacketSendEvent event) {
        try {
            var wrapper = new WrapperPlayServerRecipeBookRemove(event);
            var book = recipeBooks.get(event.getUser().getUUID());
            if (book != null) {
                wrapper.getRecipeIds().forEach(id -> book.remove(id.getId()));
            }
        } catch (Throwable t) {
            InvUI.getInstance().handleException("Failed to remove cached recipe book entries", t);
        }
    }

    /**
     * The recipe content vanilla last declared to clients: the item property sets
     * (furnace inputs, smithing slots, ...) and the real stonecutter recipes.
     */
    public record VanillaRecipeData(
        Map<ResourceLocation, RecipePropertySet> itemSets,
        List<SingleInputOptionDisplay> stonecutterRecipes
    ) {}

    private record Entry<T extends PacketWrapper<?>>(
        Function<PacketReceiveEvent, T> factory,
        Queue<? super T> queue
    ) {
        void dispatch(PacketReceiveEvent event) {
            queue.add(factory.apply(event));
        }
    }

    private static final class PlayerState {
        final Set<PacketTypeCommon> discards = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final Map<PacketTypeCommon, Predicate<PacketSendEvent>> filters = new ConcurrentHashMap<>();
        @SuppressWarnings("rawtypes")
        final Map<PacketTypeCommon, Entry> redirects = new ConcurrentHashMap<>();
        @SuppressWarnings("rawtypes")
        final Map<PacketTypeCommon, Entry> listeners = new ConcurrentHashMap<>();
        final SerialExecutor sender;

        PlayerState(Executor sendPool) {
            this.sender = new SerialExecutor(sendPool);
        }
    }

    private static final class SerialExecutor {
        private final Executor delegate;
        private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean active = new AtomicBoolean(false);

        SerialExecutor(Executor delegate) {
            this.delegate = delegate;
        }

        void execute(Runnable task) {
            tasks.add(task);
            schedule();
        }

        private void schedule() {
            if (active.compareAndSet(false, true)) {
                try {
                    delegate.execute(this::drain);
                } catch (Throwable t) {
                    active.set(false);
                    throw t;
                }
            }
        }

        private void drain() {
            try {
                Runnable task;
                while ((task = tasks.poll()) != null) {
                    try {
                        task.run();
                    } catch (Throwable t) {
                        InvUI.getInstance().handleException("Packet sender task failed", t);
                    }
                }
            } finally {
                active.set(false);
            }
            if (!tasks.isEmpty()) {
                schedule();
            }
        }
    }

    private static final class SendThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "InvUI-PacketSender-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private final class Dispatcher extends PacketListenerAbstract {
        Dispatcher() {
            super(PacketListenerPriority.HIGH);
        }

        @Override
        public void onPacketSend(PacketSendEvent event) {
            PacketTypeCommon type = event.getPacketType();

            // keep recipe caches fresh even while the packets themselves are being discarded,
            // so post-close restores always reflect the server's latest recipe state
            if (type == PacketType.Play.Server.DECLARE_RECIPES) {
                cacheDeclareRecipes(event);
            } else if (type == PacketType.Play.Server.RECIPE_BOOK_ADD) {
                cacheRecipeBookAdd(event);
            } else if (type == PacketType.Play.Server.RECIPE_BOOK_REMOVE) {
                cacheRecipeBookRemove(event);
            }

            if (!(event.getPlayer() instanceof Player player))
                return;
            PlayerState state = states.get(player.getUniqueId());
            if (state == null)
                return;
            if (state.discards.contains(type)) {
                event.setCancelled(true);
                return;
            }
            Predicate<PacketSendEvent> filter = state.filters.get(type);
            if (filter != null && filter.test(event)) {
                event.setCancelled(true);
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            if (!(event.getPlayer() instanceof Player player))
                return;
            PlayerState state = states.get(player.getUniqueId());
            if (state == null)
                return;
            PacketTypeCommon type = event.getPacketType();

            Entry listener = state.listeners.get(type);
            if (listener != null) {
                try {
                    listener.dispatch(event);
                } catch (Throwable t) {
                    InvUI.getInstance().handleException("Failed to dispatch listened packet " + type, t);
                }
            }

            Entry redirect = state.redirects.get(type);
            if (redirect != null) {
                try {
                    redirect.dispatch(event);
                } catch (Throwable t) {
                    InvUI.getInstance().handleException("Failed to dispatch redirected packet " + type, t);
                }
                event.setCancelled(true);
            }
        }
    }
}
