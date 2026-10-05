package be.wwx.hibernate;

import be.wwx.hibernate.cache.ChunkIndex;
import be.wwx.hibernate.cache.impl.BoundedChunkIndex;
import be.wwx.hibernate.command.impl.HibernateCommand;
import be.wwx.hibernate.config.ConfigManager;
import be.wwx.hibernate.config.PluginConfig;
import be.wwx.hibernate.data.model.Recipient;
import be.wwx.hibernate.listener.ChunkListener;
import be.wwx.hibernate.listener.player.PlayerJoinListener;
import be.wwx.hibernate.messaging.PlayerMessenger;
import be.wwx.hibernate.messaging.impl.AdventurePlayerMessenger;
import be.wwx.hibernate.scheduler.TaskRegistry;
import be.wwx.hibernate.service.EmptyTimer;
import be.wwx.hibernate.service.HibernateService;
import be.wwx.hibernate.service.impl.DefaultHibernateService;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

/** Composition root and lifecycle/I/O orchestration only. */
public final class HibernatePlugin extends JavaPlugin {
    private final AtomicBoolean loading = new AtomicBoolean();
    private volatile boolean closed;
    private ConfigManager configs;
    private HibernateService service;
    private TaskRegistry tasks;
    private PlayerMessenger messages;

    @Override
    public void onEnable() {
        closed = false;
        configs = new ConfigManager(getDataFolder().toPath().resolve("config.yml"), () -> getResource("config.yml"));
        ChunkIndex chunks = new BoundedChunkIndex(1);
        tasks = new TaskRegistry(this);
        service = new DefaultHibernateService(this, chunks, tasks, new EmptyTimer());
        messages = new AdventurePlayerMessenger(tasks);
        HibernateCommand command = new HibernateCommand(service, chunks, tasks, messages, this::requestConfiguration);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register("hibernate", command));
        getServer().getPluginManager().registerEvents(new ChunkListener(chunks), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(service), this);
        requestConfiguration(null, null);
    }

    private boolean requestConfiguration(Recipient recipient, Boolean enabled) {
        if (closed || !loading.compareAndSet(false, true)) return false;
        try {
            getServer().getAsyncScheduler().runNow(this, task -> {
                try {
                    if (closed) return;
                    PluginConfig replacement = configs.load(enabled);
                    onGlobal(() -> {
                        try {
                            service.configure(replacement);
                            getLogger().info("Hibernate settings loaded; mode is idle chunk cleanup (server ticks continue).");
                            if (recipient != null) {
                                String key = enabled == null ? "reloaded" : enabled ? "enabled" : "disabled";
                                messages.reply(recipient, replacement, key);
                            }
                        } finally {
                            loading.set(false);
                        }
                    });
                } catch (Exception failure) {
                    getLogger().log(Level.SEVERE, "Cannot load Hibernate configuration", failure);
                    onGlobal(() -> {
                        loading.set(false);
                        PluginConfig previous = service.config();
                        if (previous == null) getServer().getPluginManager().disablePlugin(this);
                        else if (recipient != null) messages.reply(recipient, previous, "failed");
                    });
                }
            });
        } catch (RuntimeException failure) {
            loading.set(false);
            throw failure;
        }
        return true;
    }

    private void onGlobal(Runnable action) {
        if (closed) return;
        try {
            getServer().getGlobalRegionScheduler().run(this, task -> {
                if (!closed) action.run();
            });
        } catch (RuntimeException failure) {
            if (!closed) throw failure;
        }
    }

    @Override
    public void onDisable() {
        closed = true;
        HandlerList.unregisterAll(this);
        if (service != null) service.close();
        if (tasks != null) tasks.close();
        loading.set(false);
    }
}
