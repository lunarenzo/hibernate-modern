package be.wwx.hibernate.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Invoked only by the asynchronous I/O scheduler, with one operation in flight. */
public final class ConfigManager {
    private static final int MAX_FILE_BYTES = 65_536;
    private static final List<String> MESSAGE_KEYS = List.of(
            "no-permission", "usage", "busy", "unsupported-sender", "reloaded", "enabled",
            "disabled", "failed", "status", "state-active", "state-waiting", "state-idle",
            "state-disabled", "state-blocked");
    private final Path file;
    private final Supplier<InputStream> defaults;

    public ConfigManager(Path file, Supplier<InputStream> defaults) {
        this.file = file;
        this.defaults = defaults;
    }

    /** enabled == null reloads; otherwise persists a validated enable/disable change. */
    public PluginConfig load(Boolean enabled) throws IOException, InvalidConfigurationException {
        String defaultText;
        try (InputStream input = defaults.get()) {
            if (input == null) throw new IOException("Bundled config.yml is missing");
            defaultText = read(input);
        }
        YamlConfiguration fallback = parse(defaultText);
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) write(defaultText);
        String currentText;
        try (InputStream input = Files.newInputStream(file)) {
            currentText = read(input);
        }
        YamlConfiguration yaml = parse(currentText);
        if (yaml.isSet("sleepMillis")) {
            throw new IOException("Legacy sleepMillis is unsafe and is not a countdown. Use the new config.yml and empty-delay-seconds.");
        }
        yaml.setDefaults(fallback);
        PluginConfig result = validate(yaml);
        if (enabled != null) {
            yaml.set("enabled", enabled);
            result = validate(yaml);
            write(yaml.saveToString());
        }
        return result;
    }

    public static PluginConfig validate(YamlConfiguration yaml) {
        integer(yaml, "config-version", 1, 1);
        Map<String, String> messages = new HashMap<>();
        for (String key : MESSAGE_KEYS) {
            Object value = yaml.get("messages." + key);
            if (!(value instanceof String text) || text.length() > 4096) {
                throw new IllegalArgumentException("messages." + key + " must be a string of at most 4096 characters");
            }
            messages.put(key, text);
        }
        return new PluginConfig(bool(yaml, "enabled"), integer(yaml, "empty-delay-seconds", 0, 604_800),
                integer(yaml, "check-interval-ticks", 1, 1200), bool(yaml, "unloadChunks"),
                names(yaml, "blacklist"), names(yaml, "excluded-worlds"),
                integer(yaml, "chunk-batch-size", 1, 64), integer(yaml, "max-tracked-chunks", 1, 65_536),
                integer(yaml, "max-pending-tasks", 1, 128), integer(yaml, "max-pending-replies", 1, 64), messages);
    }

    private static boolean bool(YamlConfiguration yaml, String key) {
        if (yaml.get(key) instanceof Boolean value) return value;
        throw new IllegalArgumentException(key + " must be true or false");
    }

    private static int integer(YamlConfiguration yaml, String key, int min, int max) {
        Object raw = yaml.get(key);
        if (!(raw instanceof Integer || raw instanceof Long)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        long value = ((Number) raw).longValue();
        if (value < min || value > max) throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return (int) value;
    }

    private static Set<String> names(YamlConfiguration yaml, String key) {
        Object raw = yaml.get(key);
        if (!(raw instanceof List<?> list) || list.size() > 128) {
            throw new IllegalArgumentException(key + " must be a list with at most 128 names");
        }
        Set<String> names = new HashSet<>();
        for (Object entry : list) {
            if (!(entry instanceof String name) || name.isBlank() || name.length() > 128 || name.contains("://")) {
                throw new IllegalArgumentException(key + " contains an invalid name; URLs are not supported");
            }
            names.add(name.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(names);
    }

    private static YamlConfiguration parse(String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    private static String read(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Config exceeds 64 KiB");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void write(String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Serialized config exceeds 64 KiB");
        Path temporary = Files.createTempFile(file.getParent(), "hibernate-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
