package lunatech.hibernate.service.impl;

import lunatech.hibernate.cache.ChunkIndex;
import lunatech.hibernate.config.PluginConfig;
import lunatech.hibernate.data.model.ChunkKey;
import lunatech.hibernate.data.model.IdleState;
import lunatech.hibernate.scheduler.TaskRegistry;
import lunatech.hibernate.service.EmptyTimer;
import lunatech.hibernate.service.HibernateService;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

public final class DefaultHibernateService implements HibernateService {
    private final Plugin plugin;
    private final ChunkIndex chunks;
    private final TaskRegistry tasks;
    private final EmptyTimer timer;
    private final AtomicBoolean joined = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private volatile PluginConfig config;
    private volatile IdleState state = new IdleState.Active();
    private volatile boolean idle;
    private volatile boolean closed;
    private boolean blacklisted;
    private ScheduledTask pollTask;

    public DefaultHibernateService(Plugin plugin, ChunkIndex chunks, TaskRegistry tasks, EmptyTimer timer) {
        this.plugin = plugin;
        this.chunks = chunks;
        this.tasks = tasks;
        this.timer = timer;
    }

    @Override
    public void configure(PluginConfig replacement) {
        idle = false;
        generation.incrementAndGet();
        tasks.cancelRegions();
        if (pollTask != null) pollTask.cancel();
        timer.reset();
        chunks.limit(replacement.maxTrackedChunks());
        tasks.limits(replacement.maxPendingTasks(), replacement.maxPendingReplies());
        blacklisted = false;
        for (var installed : plugin.getServer().getPluginManager().getPlugins()) {
            if (replacement.blacklist().contains(installed.getName().toLowerCase(Locale.ROOT))) {
                blacklisted = true;
                break;
            }
        }
        config = replacement;
        // Start the countdown now on reload/startup; the first scheduled poll is merely an observation.
        state = timer.update(replacement.enabled(), blacklisted,
                plugin.getServer().getOnlinePlayers().isEmpty(), joined.getAndSet(false),
                replacement.delayNanos(), System.nanoTime());
        pollTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> poll(), 1, replacement.checkIntervalTicks());
    }

    @Override
    public PluginConfig config() {
        return config;
    }

    @Override
    public IdleState state() {
        return state;
    }

    @Override
    public void playerJoined() {
        // Region-thread signal only. Invalidate already queued cleanup immediately.
        joined.set(true);
        generation.incrementAndGet();
        idle = false;
    }

    @Override
    public void poll() {
        if (closed || config == null) return;
        PluginConfig settings = config;
        long epoch = generation.get();
        state = timer.update(settings.enabled(), blacklisted,
                plugin.getServer().getOnlinePlayers().isEmpty(), joined.getAndSet(false),
                settings.delayNanos(), System.nanoTime());
        idle = state instanceof IdleState.Idle;
        if (generation.get() != epoch) {
            idle = false;
            timer.reset();
            return;
        }
        if (!idle || !settings.unloadChunks()) return;
        // Inspect at most one configured batch. No global getLoadedChunks() scan.
        int count = Math.min(settings.chunkBatchSize(), chunks.size());
        for (int i = 0; i < count; i++) {
            ChunkKey key = chunks.next();
            if (key == null) break;
            if (tasks.pendingRegions() >= settings.maxPendingTasks()) break;
            tasks.region(key, () -> cleanup(key, epoch, settings));
        }
    }

    private void cleanup(ChunkKey key, long epoch, PluginConfig settings) {
        if (closed || !idle || generation.get() != epoch) return;
        World world = plugin.getServer().getWorld(key.worldId());
        if (world == null || !world.isChunkLoaded(key.x(), key.z())) {
            chunks.remove(key);
            return;
        }
        if (settings.excludedWorlds().contains(world.getName().toLowerCase(Locale.ROOT))
                || world.isChunkForceLoaded(key.x(), key.z())
                || !world.getPluginChunkTickets(key.x(), key.z()).isEmpty()) return;
        // Recheck immediately before requesting unloading, without loading a chunk or forcing saves.
        if (closed || !idle || generation.get() != epoch) return;
        world.unloadChunkRequest(key.x(), key.z());
    }

    @Override
    public void close() {
        closed = true;
        idle = false;
        generation.incrementAndGet();
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
        tasks.cancelRegions();
        chunks.close();
    }
}
