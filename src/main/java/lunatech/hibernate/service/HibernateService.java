package lunatech.hibernate.service;

import lunatech.hibernate.config.PluginConfig;
import lunatech.hibernate.data.model.IdleState;

/** Layout-required service boundary; mutation is confined to global execution. */
public interface HibernateService {
    void configure(PluginConfig config);
    PluginConfig config();
    IdleState state();
    void playerJoined();
    void poll();
    void close();
}
