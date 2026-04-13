package xyz.xenondevs.invui.internal.network;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import xyz.xenondevs.invui.InvUI;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
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
import java.util.function.Supplier;

public class PacketListener implements Listener {
    private static final ExecutorService SEND_POOL = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
        new SendThreadFactory()
    );

    private static final PacketListener INSTANCE = new PacketListener();

    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();

    private PacketListener() {
        PacketEvents.getAPI().getEventManager().registerListener(new Dispatcher());
        Bukkit.getPluginManager().registerEvents(this, InvUI.getInstance().getPlugin());
        Bukkit.getOnlinePlayers().forEach(player -> states.put(player.getUniqueId(), new PlayerState()));
        InvUI.getInstance().addDisableHandler(() -> {
            states.clear();
            SEND_POOL.shutdown();
            try {
                SEND_POOL.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public static PacketListener getInstance() {
        return INSTANCE;
    }

    public void discard(Player player, PacketTypeCommon type) {
        getState(player.getUniqueId()).discards.add(type);
    }

    public void stopDiscard(Player player, PacketTypeCommon type) {
        getState(player.getUniqueId()).discards.remove(type);
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
        states.put(event.getPlayer().getUniqueId(), new PlayerState());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private void handleQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

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
        @SuppressWarnings("rawtypes")
        final Map<PacketTypeCommon, Entry> redirects = new ConcurrentHashMap<>();
        @SuppressWarnings("rawtypes")
        final Map<PacketTypeCommon, Entry> listeners = new ConcurrentHashMap<>();
        final SerialExecutor sender = new SerialExecutor(SEND_POOL);
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
            if (!(event.getPlayer() instanceof Player player))
                return;
            PlayerState state = states.get(player.getUniqueId());
            if (state == null)
                return;
            if (state.discards.contains(event.getPacketType())) {
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
