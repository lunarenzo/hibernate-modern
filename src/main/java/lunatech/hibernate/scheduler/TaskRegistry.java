package lunatech.hibernate.scheduler;

import lunatech.hibernate.data.model.ChunkKey;
import lunatech.hibernate.data.model.Recipient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/** Owns bounded, cancellable region/entity work. Scheduler handles are released on completion. */
public final class TaskRegistry {
    private final Plugin plugin;
    private final Map<Object, Ticket> regions = new HashMap<>();
    private final Map<Object, Ticket> replies = new HashMap<>();
    private volatile boolean closed;
    private int regionLimit = 1;
    private int replyLimit = 1;

    public TaskRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void limits(int regions, int replies) {
        regionLimit = regions;
        replyLimit = replies;
    }

    public boolean region(ChunkKey key, Runnable action) {
        Ticket ticket = reserve(regions, key, false);
        if (ticket == null) return false;
        try {
            World world = plugin.getServer().getWorld(key.worldId());
            if (world == null) {
                ticket.complete();
                return false;
            }
            ticket.bind(plugin.getServer().getRegionScheduler().run(plugin, world, key.x(), key.z(), task -> {
                try {
                    if (!closed) action.run();
                } finally {
                    ticket.complete();
                }
            }));
            return true;
        } catch (RuntimeException failure) {
            ticket.complete();
            throw failure;
        }
    }

    /** Called from the global scheduler after I/O completion. */
    public void reply(Recipient recipient, Component text) {
        if (closed) return;
        switch (recipient) {
            case Recipient.ConsoleRecipient ignored -> plugin.getServer().getConsoleSender().sendMessage(text);
            case Recipient.PlayerRecipient playerRecipient -> {
                Object key = new Object();
                Ticket ticket = reserve(replies, key, true);
                if (ticket == null) return;
                try {
                    Player player = plugin.getServer().getPlayer(playerRecipient.id());
                    if (player == null) {
                        ticket.complete();
                        return;
                    }
                    // The closure contains UUID/text/ticket, never a retained live Player.
                    ScheduledTask scheduled = player.getScheduler().run(plugin, task -> {
                        try {
                            if (!closed) {
                                Player current = plugin.getServer().getPlayer(playerRecipient.id());
                                if (current != null) current.sendMessage(text);
                            }
                        } finally {
                            ticket.complete();
                        }
                    }, ticket::complete);
                    if (scheduled == null) ticket.complete();
                    else ticket.bind(scheduled);
                } catch (RuntimeException failure) {
                    ticket.complete();
                    throw failure;
                }
            }
        }
    }

    public synchronized int pendingRegions() {
        return regions.size();
    }

    public void cancelRegions() {
        ArrayList<Ticket> tickets;
        synchronized (this) {
            tickets = new ArrayList<>(regions.values());
        }
        tickets.forEach(Ticket::cancel);
    }

    public void close() {
        ArrayList<Ticket> tickets;
        synchronized (this) {
            closed = true;
            tickets = new ArrayList<>(regions.values());
            tickets.addAll(replies.values());
        }
        tickets.forEach(Ticket::cancel);
        plugin.getServer().getGlobalRegionScheduler().cancelTasks(plugin);
        plugin.getServer().getAsyncScheduler().cancelTasks(plugin);
    }

    private synchronized Ticket reserve(Map<Object, Ticket> map, Object key, boolean reply) {
        if (closed || map.size() >= (reply ? replyLimit : regionLimit) || map.containsKey(key)) return null;
        Ticket ticket = new Ticket(map, key);
        // Register before scheduling: callbacks may run before the handle is returned.
        map.put(key, ticket);
        return ticket;
    }

    private final class Ticket {
        private final Map<Object, Ticket> map;
        private final Object key;
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicReference<ScheduledTask> handle = new AtomicReference<>();

        private Ticket(Map<Object, Ticket> map, Object key) {
            this.map = map;
            this.key = key;
        }

        private void bind(ScheduledTask task) {
            handle.set(task);
            if (completed.get()) task.cancel();
        }

        private void complete() {
            if (!completed.compareAndSet(false, true)) return;
            synchronized (TaskRegistry.this) {
                map.remove(key, this);
            }
            handle.set(null);
        }

        private void cancel() {
            ScheduledTask task = handle.get();
            complete();
            if (task != null) task.cancel();
        }
    }
}
