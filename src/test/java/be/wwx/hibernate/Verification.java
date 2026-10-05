package be.wwx.hibernate;

import be.wwx.hibernate.cache.impl.BoundedChunkIndex;
import be.wwx.hibernate.config.ConfigManager;
import be.wwx.hibernate.config.PluginConfig;
import be.wwx.hibernate.data.model.ChunkKey;
import be.wwx.hibernate.data.model.IdleState;
import be.wwx.hibernate.data.model.Recipient;
import be.wwx.hibernate.scheduler.TaskRegistry;
import be.wwx.hibernate.service.EmptyTimer;
import be.wwx.hibernate.service.impl.DefaultHibernateService;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/** Dependency-free test runner: failing checks throw and fail Gradle check. */
public final class Verification {
    private static int checks;

    private Verification() { }

    public static void main(String[] args) throws Exception {
        timer();
        index();
        configuration();
        scheduling();
        System.out.println("Verified " + checks + " assertions: countdown, bounded concurrent cache, config I/O, region ownership, wakeup, cancellation and replies.");
    }

    private static void timer() {
        EmptyTimer timer = new EmptyTimer();
        long delay = TimeUnit.MINUTES.toNanos(10);
        long start = 1234;
        check(timer.update(true, false, true, false, delay, start) instanceof IdleState.Waiting, "starts waiting");
        check(timer.update(true, false, true, false, delay, start + delay - 1) instanceof IdleState.Waiting, "not before ten minutes");
        check(timer.update(true, false, true, false, delay, start + delay) instanceof IdleState.Idle, "exact ten-minute boundary");
        check(timer.update(true, false, false, true, delay, start + delay + 1) instanceof IdleState.Active, "player wakes idle state");
        long next = start + delay + 2;
        timer.update(true, false, true, false, delay, next);
        long briefJoin = next + delay - 1;
        check(timer.update(true, false, true, true, delay, briefJoin) instanceof IdleState.Waiting, "join and quit between polls resets timer");
        check(timer.update(true, false, true, false, delay, briefJoin + delay - 1) instanceof IdleState.Waiting, "restart retains full delay");
        check(timer.update(true, false, true, false, delay, briefJoin + delay) instanceof IdleState.Idle, "restart eventually idles");
        check(timer.update(false, false, true, false, delay, 0) instanceof IdleState.Disabled, "disabled wins");
        check(timer.update(true, true, true, false, delay, 0) instanceof IdleState.Blocked, "blacklist blocks");
        timer.reset();
        check(timer.update(true, false, true, false, 0, 0) instanceof IdleState.Idle, "zero delay immediate");
        timer.reset();
        long nearWrap = Long.MAX_VALUE - 10;
        timer.update(true, false, true, false, 20, nearWrap);
        check(timer.update(true, false, true, false, 20, nearWrap + 19) instanceof IdleState.Waiting, "nanoTime wrapping before threshold");
        check(timer.update(true, false, true, false, 20, nearWrap + 20) instanceof IdleState.Idle, "nanoTime wrapping at threshold");
    }

    private static void index() throws Exception {
        UUID world = UUID.randomUUID();
        BoundedChunkIndex index = new BoundedChunkIndex(2);
        ChunkKey negative = new ChunkKey(world, -1, -5);
        ChunkKey other = new ChunkKey(world, 2, 3);
        index.add(negative);
        index.add(negative);
        index.add(other);
        check(index.size() == 2, "duplicate does not consume capacity");
        check(index.next().equals(negative) && index.next().equals(other), "round-robin coordinates preserved");
        index.add(new ChunkKey(UUID.randomUUID(), 0, 0));
        check(index.size() == 2, "full cache evicts without growing");
        index.removeWorld(world);
        check(index.size() == 1, "world cleanup removes only its keys");
        index.limit(1);
        index.close();
        index.add(negative);
        check(index.size() == 0 && index.next() == null, "closed index cannot be repopulated");

        BoundedChunkIndex concurrent = new BoundedChunkIndex(64);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int worker = 0; worker < 8; worker++) {
            int id = worker;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 3000; i++) {
                        ChunkKey key = new ChunkKey(world, id, i);
                        concurrent.add(key);
                        concurrent.next();
                        if (concurrent.size() > 64) throw new AssertionError("Concurrent cache exceeded capacity");
                        if ((i & 3) == 0) concurrent.remove(key);
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        check(failure.get() == null, "concurrent add/remove/rotation has no failures");
        check(concurrent.size() <= 64, "concurrent cache stays bounded");
        concurrent.limit(8);
        check(concurrent.size() <= 8, "capacity reduction trims existing keys");
        concurrent.close();
    }

    private static void configuration() throws Exception {
        String bundled;
        try (InputStream input = Verification.class.getResourceAsStream("/config.yml")) {
            if (input == null) throw new AssertionError("Missing config resource");
            bundled = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path directory = Files.createTempDirectory("hibernate-config-test-");
        Path file = directory.resolve("config.yml");
        try {
            ConfigManager manager = new ConfigManager(file,
                    () -> new ByteArrayInputStream(bundled.getBytes(StandardCharsets.UTF_8)));
            PluginConfig initial = manager.load(null);
            check(initial.enabled() && initial.emptyDelaySeconds() == 600, "bundled ten-minute default loads");
            check(initial.maxTrackedChunks() == 4096 && initial.maxPendingTasks() == 32, "default bounds load");
            check(Files.readString(file).equals(bundled), "first install preserves bundled comments");
            check(!manager.load(false).enabled() && !manager.load(null).enabled(), "disabled setting persists");
            check(manager.load(true).enabled(), "enable persists");
            String valid = Files.readString(file);
            Files.writeString(file, "config-version: 1\nenabled: true\n");
            check(manager.load(null).emptyDelaySeconds() == 600, "missing keys use defaults");
            Files.writeString(file, valid.replace("empty-delay-seconds: 600", "empty-delay-seconds: -1"));
            expectFailure(() -> manager.load(null), "negative delay rejected");
            String invalid = Files.readString(file);
            expectFailure(() -> manager.load(false), "toggle refuses invalid config");
            check(Files.readString(file).equals(invalid), "failed operation does not overwrite invalid config");
            Files.writeString(file, valid.replace("enabled: true", "enabled: yesplease"));
            expectFailure(() -> manager.load(null), "mistyped boolean rejected");
            Files.writeString(file, valid.replace("chunk-batch-size: 8", "chunk-batch-size: 8.5"));
            expectFailure(() -> manager.load(null), "fractional batch rejected");
            Files.writeString(file, valid + "\nsleepMillis: 600000\n");
            expectFailure(() -> manager.load(null), "legacy sleeping setting rejected");
            Files.writeString(file, "[broken YAML");
            expectFailure(() -> manager.load(null), "malformed YAML rejected");
            Files.writeString(file, "x".repeat(65_537));
            expectFailure(() -> manager.load(null), "oversized config rejected before parsing");
            Files.writeString(file, valid.replace("blacklist: []", "blacklist: ['https://example.invalid/list']"));
            expectFailure(() -> manager.load(null), "remote blacklist rejected");
            Files.writeString(file, valid);
            check(manager.load(null).enabled(), "recovering valid file succeeds");
            try (var files = Files.list(directory)) {
                check(files.count() == 1, "no temporary file remains");
            }
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static void scheduling() throws Exception {
        FakeServer fake = new FakeServer();
        TaskRegistry tasks = new TaskRegistry(fake.plugin);
        BoundedChunkIndex chunks = new BoundedChunkIndex(32);
        DefaultHibernateService service = new DefaultHibernateService(fake.plugin, chunks, tasks, new EmptyTimer());
        PluginConfig config = testConfig();
        service.configure(config);
        ChunkKey eligible = fake.key(-5, -1);
        ChunkKey forced = fake.key(1, 2);
        ChunkKey ticketed = fake.key(3, 4);
        fake.forced.add(forced);
        fake.ticketed.add(ticketed);
        chunks.add(eligible);
        chunks.add(forced);
        chunks.add(ticketed);
        service.poll();
        check(service.state() instanceof IdleState.Idle, "zero-delay service enters cleanup mode");
        check(tasks.pendingRegions() == 2, "region queue obeys configured capacity");
        fake.drainRegions();
        service.poll();
        fake.drainRegions();
        check(fake.unloaded.contains(eligible), "eligible negative-coordinate chunk gets requested");
        check(!fake.unloaded.contains(forced) && !fake.unloaded.contains(ticketed), "forced and plugin-ticketed chunks skipped");
        check(tasks.pendingRegions() == 0, "completed tasks release slots");

        fake.unloaded.clear();
        service.poll();
        service.playerJoined();
        fake.drainRegions();
        check(fake.unloaded.isEmpty(), "join invalidates queued cleanup before next global poll");
        fake.online = 1;
        service.poll();
        check(service.state() instanceof IdleState.Active && tasks.pendingRegions() == 0, "online player prevents cleanup");

        fake.online = 0;
        service.poll();
        check(tasks.pendingRegions() > 0, "cleanup resumes with configured zero delay");
        service.configure(config);
        check(tasks.pendingRegions() == 0, "reload cancels old queued regions");
        fake.unloaded.clear();
        fake.drainRegions();
        check(fake.unloaded.isEmpty(), "cancelled tasks do not execute");

        fake.immediate = true;
        tasks.region(eligible, () -> { });
        check(tasks.pendingRegions() == 0, "completion before returned handle does not leak a slot");
        fake.immediate = false;
        check(tasks.region(eligible, () -> { }) && !tasks.region(eligible, () -> { }), "same chunk cannot be queued twice");
        tasks.cancelRegions();
        check(tasks.pendingRegions() == 0, "explicit cancellation releases region slots");
        fake.drainRegions();

        var text = MiniMessage.miniMessage().deserialize("hello");
        tasks.reply(new Recipient.PlayerRecipient(fake.playerId), text);
        tasks.reply(new Recipient.PlayerRecipient(fake.playerId), text);
        tasks.reply(new Recipient.PlayerRecipient(fake.playerId), text);
        check(fake.entities.size() == 2, "entity reply queue stays bounded");
        fake.drainEntities();
        check(fake.playerMessages.get() == 2, "replies execute on entity scheduler");
        tasks.reply(new Recipient.PlayerRecipient(fake.playerId), text);
        fake.retireEntity();
        tasks.reply(new Recipient.PlayerRecipient(fake.playerId), text);
        check(fake.entities.size() == 1, "retired entity releases reply slot");
        tasks.reply(new Recipient.ConsoleRecipient(), text);
        check(fake.consoleMessages.get() == 1, "console reply runs globally");

        service.close();
        tasks.close();
        fake.drainEntities();
        check(chunks.size() == 0 && tasks.pendingRegions() == 0, "shutdown clears cache and pending tasks");
        check(!tasks.region(eligible, () -> { throw new AssertionError("Closed task ran"); }), "shutdown rejects new work");
        check(fake.playerMessages.get() == 2, "shutdown cancels entity replies");
    }

    private static PluginConfig testConfig() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream input = Verification.class.getResourceAsStream("/config.yml")) {
            if (input == null) throw new AssertionError("Missing config resource");
            yaml.loadFromString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        yaml.set("empty-delay-seconds", 0);
        yaml.set("max-pending-tasks", 2);
        yaml.set("max-pending-replies", 2);
        return ConfigManager.validate(yaml);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void expectFailure(CheckedAction action, String message) throws Exception {
        try {
            action.run();
        } catch (Exception expected) {
            checks++;
            return;
        }
        throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private static <T> T proxy(Class<T> type, InvocationHandler calls) {
        Object result = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            return switch (method.getName()) {
                case "toString" -> "Test " + type.getSimpleName();
                case "hashCode" -> System.identityHashCode(object);
                case "equals" -> object == args[0];
                default -> calls.invoke(object, method, args);
            };
        });
        return type.cast(result);
    }

    /** Strict public-API doubles: world operations fail unless inside their owning region. */
    private static final class FakeServer {
        private final UUID worldId = UUID.randomUUID();
        private final UUID playerId = UUID.randomUUID();
        private final Set<ChunkKey> forced = new HashSet<>();
        private final Set<ChunkKey> ticketed = new HashSet<>();
        private final Set<ChunkKey> unloaded = new HashSet<>();
        private final ArrayDeque<Pending> regions = new ArrayDeque<>();
        private final ArrayDeque<Pending> entities = new ArrayDeque<>();
        private final AtomicInteger playerMessages = new AtomicInteger();
        private final AtomicInteger consoleMessages = new AtomicInteger();
        private ChunkKey owningRegion;
        private boolean entityContext;
        private boolean immediate;
        private int online;
        private final Plugin plugin;

        private FakeServer() {
            AtomicReference<Server> server = new AtomicReference<>();
            plugin = proxy(Plugin.class, (object, method, args) -> switch (method.getName()) {
                case "getServer" -> server.get();
                case "getName" -> "Hibernate";
                default -> throw new AssertionError("Unexpected plugin API: " + method);
            });
            World world = proxy(World.class, (object, method, args) -> {
                if (method.getName().equals("getName")) return "world";
                ChunkKey key = key((Integer) args[0], (Integer) args[1]);
                if (!key.equals(owningRegion)) throw new AssertionError("World access outside owner: " + method);
                return switch (method.getName()) {
                    case "isChunkLoaded" -> true;
                    case "isChunkForceLoaded" -> forced.contains(key);
                    case "getPluginChunkTickets" -> ticketed.contains(key) ? List.of(plugin) : List.of();
                    case "unloadChunkRequest" -> unloaded.add(key);
                    default -> throw new AssertionError("Unexpected world API: " + method);
                };
            });
            EntityScheduler entityScheduler = proxy(EntityScheduler.class, (object, method, args) -> {
                if (!method.getName().equals("run")) throw new AssertionError("Unexpected entity API: " + method);
                Pending pending = pending(args[1], null, (Runnable) args[2]);
                entities.add(pending);
                return pending.task;
            });
            Player player = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getScheduler" -> entityScheduler;
                case "sendMessage" -> {
                    if (!entityContext) throw new AssertionError("Player message outside entity scheduler");
                    playerMessages.incrementAndGet();
                    yield null;
                }
                default -> throw new AssertionError("Unexpected player API: " + method);
            });
            ConsoleCommandSender console = proxy(ConsoleCommandSender.class, (object, method, args) -> {
                if (!method.getName().equals("sendMessage")) throw new AssertionError("Unexpected console API: " + method);
                consoleMessages.incrementAndGet();
                return null;
            });
            GlobalRegionScheduler global = proxy(GlobalRegionScheduler.class, (object, method, args) -> {
                if (method.getName().equals("cancelTasks")) return null;
                if (!method.getName().equals("runAtFixedRate")) throw new AssertionError("Unexpected global API: " + method);
                return pending(args[1], null, null).task;
            });
            RegionScheduler region = proxy(RegionScheduler.class, (object, method, args) -> {
                if (!method.getName().equals("run")) throw new AssertionError("Unexpected region API: " + method);
                Pending pending = pending(args[4], key((Integer) args[2], (Integer) args[3]), null);
                if (immediate) run(pending, false);
                else regions.add(pending);
                return pending.task;
            });
            AsyncScheduler async = proxy(AsyncScheduler.class, (object, method, args) -> {
                if (!method.getName().equals("cancelTasks")) throw new AssertionError("Unexpected async API: " + method);
                return null;
            });
            server.set(proxy(Server.class, (object, method, args) -> switch (method.getName()) {
                case "getWorld" -> worldId.equals(args[0]) ? world : null;
                case "getPlayer" -> playerId.equals(args[0]) ? player : null;
                case "getOnlinePlayers" -> Collections.nCopies(online, player);
                case "getRegionScheduler" -> region;
                case "getGlobalRegionScheduler" -> global;
                case "getAsyncScheduler" -> async;
                case "getPluginManager" -> proxy(PluginManager.class, (manager, api, parameters) -> {
                    if (!api.getName().equals("getPlugins")) throw new AssertionError("Unexpected plugin manager API: " + api);
                    return new Plugin[0];
                });
                case "getConsoleSender" -> console;
                default -> throw new AssertionError("Unexpected server API: " + method);
            }));
        }

        private ChunkKey key(int x, int z) {
            return new ChunkKey(worldId, x, z);
        }

        @SuppressWarnings("unchecked")
        private Pending pending(Object callback, ChunkKey key, Runnable retired) {
            Pending pending = new Pending((Consumer<ScheduledTask>) callback, key, retired);
            pending.task = proxy(ScheduledTask.class, (object, method, args) -> {
                if (!method.getName().equals("cancel")) throw new AssertionError("Unexpected task API: " + method);
                pending.cancelled = true;
                return null; // The cancellation result is deliberately ignored by production code.
            });
            return pending;
        }

        private void drainRegions() {
            while (!regions.isEmpty()) run(regions.remove(), false);
        }

        private void drainEntities() {
            while (!entities.isEmpty()) run(entities.remove(), true);
        }

        private void retireEntity() {
            Pending pending = entities.remove();
            if (pending.retired != null) pending.retired.run();
        }

        private void run(Pending pending, boolean entity) {
            if (pending.cancelled) return;
            owningRegion = pending.key;
            entityContext = entity;
            try {
                pending.callback.accept(pending.task);
            } finally {
                owningRegion = null;
                entityContext = false;
            }
        }
    }

    private static final class Pending {
        private final Consumer<ScheduledTask> callback;
        private final ChunkKey key;
        private final Runnable retired;
        private ScheduledTask task;
        private boolean cancelled;

        private Pending(Consumer<ScheduledTask> callback, ChunkKey key, Runnable retired) {
            this.callback = callback;
            this.key = key;
            this.retired = retired;
        }
    }
}
