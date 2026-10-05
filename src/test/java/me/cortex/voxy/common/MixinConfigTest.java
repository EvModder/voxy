package me.cortex.voxy.common;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MixinConfigTest {
    @Test
    void optionalIntegrationsDoNotDisableRequiredHooks() throws Exception {
        var optional = Set.of("flashback", "iris", "nvidium", "chunky");
        for (String resource : new String[]{"client.voxy.mixins.json", "common.voxy.mixins.json"}) {
            try (var reader = new InputStreamReader(getClass().getClassLoader().getResourceAsStream(resource))) {
                var config = JsonParser.parseReader(reader).getAsJsonObject();
                assertEquals(MixinConfig.class.getName(), config.get("plugin").getAsString());
                String prefix = config.get("package").getAsString();
                var absent = new MixinConfig(mod -> false);
                var present = new MixinConfig(optional::contains);
                absent.onLoad(prefix);
                present.onLoad(prefix);
                for (var entry : config.getAsJsonArray(config.has("client") ? "client" : "mixins")) {
                    String name = entry.getAsString();
                    String group = name.substring(0, name.indexOf('.'));
                    assertEquals(!optional.contains(group), absent.shouldApplyMixin("unused", prefix + "." + name), name);
                    assertTrue(present.shouldApplyMixin("unused", prefix + "." + name), name);
                }
            }
        }
    }
}
