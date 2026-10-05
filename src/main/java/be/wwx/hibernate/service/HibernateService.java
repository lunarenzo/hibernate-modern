package be.wwx.hibernate.service;

import be.wwx.hibernate.config.PluginConfig;
import be.wwx.hibernate.data.model.IdleState;

/** Layout-required service boundary; mutation is confined to global execution. */
public interface HibernateService {
    void configure(PluginConfig config);
    PluginConfig config();
    IdleState state();
    void playerJoined();
    void poll();
    void close();
}
