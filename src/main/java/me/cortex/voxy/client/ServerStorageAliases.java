package me.cortex.voxy.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.common.Logger;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ServerStorageAliases {
    private static final int FORMAT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern INVALID_STORAGE_KEY_CHARACTERS = Pattern.compile("[^A-Za-z0-9._-]");

    private ServerStorageAliases() {
    }

    public record Resolution(String storageKey) {
    }

    public static Resolution resolve(Path gameDirectory, String serverName, String serverAddress) {
        Config config = load(gameDirectory.resolve(".voxy").resolve("server_aliases.json"));
        String name = normalize(serverName);
        String address = normalize(serverAddress);
        for (Alias alias : config.aliases) {
            if (alias == null) {
                continue;
            }
            if (matchesAny(alias.serverNamePatterns, name) || matchesAny(alias.addressPatterns, address)) {
                try {
                    String storageKey = sanitizeStorageKey(alias.logicalServer);
                    Logger.info("Using logical server storage '" + storageKey + "' for " + serverAddress);
                    return new Resolution(storageKey);
                } catch (IllegalArgumentException exception) {
                    Logger.error("Ignoring invalid Voxy server alias", exception);
                }
            }
        }
        String storageKey = serverAddress == null || serverAddress.isBlank()
                ? "unknown_server"
                : serverAddress.replace(':', '_');
        return new Resolution(storageKey);
    }

    static boolean wildcardMatches(String pattern, String value) {
        pattern = normalize(pattern);
        value = normalize(value);
        int patternIndex = 0;
        int valueIndex = 0;
        int wildcardIndex = -1;
        int wildcardValueIndex = -1;
        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == value.charAt(valueIndex)) {
                patternIndex++;
                valueIndex++;
            } else if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                wildcardIndex = patternIndex++;
                wildcardValueIndex = valueIndex;
            } else if (wildcardIndex >= 0) {
                patternIndex = wildcardIndex + 1;
                valueIndex = ++wildcardValueIndex;
            } else {
                return false;
            }
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }

    private static boolean matchesAny(List<String> patterns, String value) {
        if (patterns == null || value.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern != null && wildcardMatches(pattern, value)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String sanitizeStorageKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("Logical server name cannot be blank");
        }
        String sanitized = INVALID_STORAGE_KEY_CHARACTERS.matcher(storageKey.trim()).replaceAll("_");
        if (sanitized.equals(".") || sanitized.equals("..")) {
            throw new IllegalArgumentException("Invalid logical server name: " + storageKey);
        }
        return sanitized;
    }

    private static synchronized Config load(Path configPath) {
        Config defaults = createDefaults();
        try {
            Files.createDirectories(configPath.getParent());
            Path lockPath = configPath.resolveSibling(configPath.getFileName() + ".lock");
            try (var lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = lockChannel.lock()) {
                if (!Files.exists(configPath)) {
                    writeAtomically(configPath, GSON.toJson(defaults));
                    return defaults;
                }
                Config config = GSON.fromJson(Files.readString(configPath), Config.class);
                if (config == null || config.version != FORMAT_VERSION || config.aliases == null) {
                    Logger.error("Invalid Voxy server alias config; using built-in defaults without overwriting it");
                    return defaults;
                }
                return config;
            }
        } catch (Exception exception) {
            Logger.error("Unable to load Voxy server alias config; using built-in defaults", exception);
            return defaults;
        }
    }

    private static void writeAtomically(Path destination, String contents) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, contents);
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Config createDefaults() {
        return new Config();
    }

    private static final class Config {
        int version = FORMAT_VERSION;
        List<Alias> aliases = new ArrayList<>();
    }

    private static final class Alias {
        String logicalServer;
        List<String> serverNamePatterns = new ArrayList<>();
        List<String> addressPatterns = new ArrayList<>();
    }
}
