package lunatech.hibernate.messaging;

import lunatech.hibernate.config.PluginConfig;
import lunatech.hibernate.data.model.IdleState;
import lunatech.hibernate.data.model.Recipient;
import org.bukkit.command.CommandSender;

/** Layout-required presentation boundary. */
public interface PlayerMessenger {
    void send(CommandSender sender, PluginConfig config, String key);
    void reply(Recipient recipient, PluginConfig config, String key);
    void status(CommandSender sender, PluginConfig config, IdleState state, int tracked, int pending);
}
