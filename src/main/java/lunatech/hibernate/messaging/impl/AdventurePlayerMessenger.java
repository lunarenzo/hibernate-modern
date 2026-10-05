package lunatech.hibernate.messaging.impl;

import lunatech.hibernate.config.PluginConfig;
import lunatech.hibernate.data.model.IdleState;
import lunatech.hibernate.data.model.Recipient;
import lunatech.hibernate.messaging.PlayerMessenger;
import lunatech.hibernate.scheduler.TaskRegistry;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

public final class AdventurePlayerMessenger implements PlayerMessenger {
    private final TaskRegistry tasks;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public AdventurePlayerMessenger(TaskRegistry tasks) {
        this.tasks = tasks;
    }

    @Override
    public void send(CommandSender sender, PluginConfig config, String key) {
        sender.sendMessage(miniMessage.deserialize(config.message(key)));
    }

    @Override
    public void reply(Recipient recipient, PluginConfig config, String key) {
        tasks.reply(recipient, miniMessage.deserialize(config.message(key)));
    }

    @Override
    public void status(CommandSender sender, PluginConfig config, IdleState state, int tracked, int pending) {
        String key = switch (state) {
            case IdleState.Active ignored -> "state-active";
            case IdleState.Waiting ignored -> "state-waiting";
            case IdleState.Idle ignored -> "state-idle";
            case IdleState.Disabled ignored -> "state-disabled";
            case IdleState.Blocked ignored -> "state-blocked";
        };
        String text = config.message("status").replace("{state}", config.message(key))
                .replace("{tracked}", Integer.toString(tracked)).replace("{pending}", Integer.toString(pending));
        sender.sendMessage(miniMessage.deserialize(text));
    }
}
