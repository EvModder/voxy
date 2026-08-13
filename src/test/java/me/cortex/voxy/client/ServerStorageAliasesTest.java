package me.cortex.voxy.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerStorageAliasesTest {
    @Test
    void matchesConfiguredProxyPatternsCaseInsensitively() {
        assertTrue(ServerStorageAliases.wildcardMatches("proxy.example:*", "proxy.example:25566"));
        assertTrue(ServerStorageAliases.wildcardMatches("Account Proxy:*", "account proxy:Example"));
        assertTrue(ServerStorageAliases.wildcardMatches("192.0.2.10:*", "192.0.2.10:25567"));
        assertFalse(ServerStorageAliases.wildcardMatches("proxy.example:*", "other.example"));
    }

    @Test
    void preservesPerAddressStorageForUnmatchedServers(@TempDir Path gameDirectory) throws Exception {
        var resolution = ServerStorageAliases.resolve(
                gameDirectory, "Example server", "server.example:25565");

        assertEquals("server.example_25565", resolution.storageKey());
        assertFalse(resolution.sharedStorage());
        assertTrue(Files.readString(gameDirectory.resolve(".voxy/server_aliases.json"))
                .contains("\"aliases\": []"));
    }

    @Test
    void honorsUserDefinedAddressPatterns(@TempDir Path gameDirectory) throws Exception {
        Path configDirectory = gameDirectory.resolve(".voxy");
        Files.createDirectories(configDirectory);
        Files.writeString(configDirectory.resolve("server_aliases.json"), """
                {
                  "version": 1,
                  "aliases": [
                    {
                      "logicalServer": "shared.example",
                      "sharedStorage": true,
                      "serverNamePatterns": [],
                      "addressPatterns": ["198.51.100.10:*"]
                    }
                  ]
                }
                """);

        var resolution = ServerStorageAliases.resolve(
                gameDirectory, "Example proxy", "198.51.100.10:25565");

        assertEquals("shared.example", resolution.storageKey());
        assertTrue(resolution.sharedStorage());
    }
}
