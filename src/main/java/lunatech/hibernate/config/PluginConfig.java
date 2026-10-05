package lunatech.hibernate.config;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public record PluginConfig(boolean enabled, long emptyDelaySeconds, long checkIntervalTicks,
                           boolean unloadChunks, Set<String> blacklist, Set<String> excludedWorlds,
                           int chunkBatchSize, int maxTrackedChunks, int maxPendingTasks,
                           int maxPendingReplies, Map<String, String> messages) {
    public PluginConfig {
        blacklist = Set.copyOf(blacklist);
        excludedWorlds = Set.copyOf(excludedWorlds);
        messages = Map.copyOf(messages);
    }

    public long delayNanos() {
        return TimeUnit.SECONDS.toNanos(emptyDelaySeconds);
    }

    public String message(String key) {
        String text = messages.get(key);
        if (text == null) throw new IllegalArgumentException("Unknown message key: " + key);
        return text;
    }
}
