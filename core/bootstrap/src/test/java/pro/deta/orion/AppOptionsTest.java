package pro.deta.orion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppOptionsTest {
    @Test
    void parsesConfigOptionWithSeparateValue() {
        AppOptions options = AppOptions.parse(new String[]{"--config", "/etc/orion/orion.yml"});

        assertEquals("/etc/orion/orion.yml", options.configurationLocation());
        assertFalse(options.helpRequested());
    }

    @Test
    void parsesShortConfigOption() {
        AppOptions options = AppOptions.parse(new String[]{"-c", "classpath://config.toml"});

        assertEquals("classpath://config.toml", options.configurationLocation());
    }

    @Test
    void parsesConfigOptionWithEqualsValue() {
        AppOptions options = AppOptions.parse(new String[]{"--config=config.yml"});

        assertEquals("config.yml", options.configurationLocation());
    }

    @Test
    void parsesHelpOption() {
        AppOptions options = AppOptions.parse(new String[]{"--help"});

        assertTrue(options.helpRequested());
        assertNull(options.configurationLocation());
    }

    @Test
    void rejectsUnknownOption() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> AppOptions.parse(new String[]{"--verbose"}));

        assertEquals("Unknown option: --verbose", error.getMessage());
    }

    @Test
    void rejectsConfigWithoutValue() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> AppOptions.parse(new String[]{"--config"}));

        assertEquals("--config requires a value", error.getMessage());
    }

    @Test
    void rejectsDuplicateConfig() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> AppOptions.parse(new String[]{"--config", "one.yml", "--config", "two.yml"}));

        assertEquals("Configuration location is already specified", error.getMessage());
    }
}
