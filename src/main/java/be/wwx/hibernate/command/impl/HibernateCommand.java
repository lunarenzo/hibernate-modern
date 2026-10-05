package be.wwx.hibernate.command.impl;

import be.wwx.hibernate.cache.ChunkIndex;
import be.wwx.hibernate.config.PluginConfig;
import be.wwx.hibernate.data.model.Recipient;
import be.wwx.hibernate.messaging.PlayerMessenger;
import be.wwx.hibernate.scheduler.TaskRegistry;
import be.wwx.hibernate.service.HibernateService;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

public final class HibernateCommand implements BasicCommand {
    private static final String PERMISSION = "hibernate.toggle";
    private static final List<String> OPTIONS = List.of("status", "reload", "enable", "disable", "toggle");
    private final HibernateService service;
    private final ChunkIndex chunks;
    private final TaskRegistry tasks;
    private final PlayerMessenger messages;
    private final BiFunction<Recipient, Boolean, Boolean> configuration;

    public HibernateCommand(HibernateService service, ChunkIndex chunks, TaskRegistry tasks,
                            PlayerMessenger messages, BiFunction<Recipient, Boolean, Boolean> configuration) {
        this.service = service;
        this.chunks = chunks;
        this.tasks = tasks;
        this.messages = messages;
        this.configuration = configuration;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        PluginConfig config = service.config();
        // Async startup has not published any configuration/messages yet.
        if (config == null) return;
        CommandSender sender = source.getSender();
        if (!sender.hasPermission(PERMISSION)) {
            messages.send(sender, config, "no-permission");
            return;
        }
        String option = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (args.length > 1 || !OPTIONS.contains(option)) {
            messages.send(sender, config, "usage");
            return;
        }
        if (option.equals("status")) {
            messages.status(sender, config, service.state(), chunks.size(), tasks.pendingRegions());
            return;
        }
        Recipient recipient;
        if (sender instanceof Player player) recipient = new Recipient.PlayerRecipient(player.getUniqueId());
        else if (sender instanceof ConsoleCommandSender) recipient = new Recipient.ConsoleRecipient();
        else {
            messages.send(sender, config, "unsupported-sender");
            return;
        }
        Boolean enabled = switch (option) {
            case "enable" -> Boolean.TRUE;
            case "disable" -> Boolean.FALSE;
            case "toggle" -> !config.enabled();
            default -> null;
        };
        if (!configuration.apply(recipient, enabled)) messages.send(sender, config, "busy");
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!source.getSender().hasPermission(PERMISSION) || args.length > 1) return List.of();
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return OPTIONS.stream().filter(option -> option.startsWith(prefix)).toList();
    }
}
