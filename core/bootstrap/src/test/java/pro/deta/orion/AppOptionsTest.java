package pro.deta.orion;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppOptionsTest {
    @Test
    void parsesConfigOptionWithSeparateValue() {
        AppOptions options = AppOptions.parse(new String[]{"--config", "/etc/orion/orion.yml"});

        assertEquals(AppOptions.Command.RUN, options.command());
        assertEquals("/etc/orion/orion.yml", options.configurationLocation());
        assertFalse(options.helpRequested());
    }

    @Test
    void parsesShortConfigOption() {
        AppOptions options = AppOptions.parse(new String[]{"-c", "classpath://config.toml"});

        assertEquals(AppOptions.Command.RUN, options.command());
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

        assertEquals(AppOptions.Command.RUN, options.command());
        assertTrue(options.helpRequested());
        assertNull(options.configurationLocation());
    }

    @Test
    void parsesRunCommandWithApplicationOptions() {
        AppOptions options = AppOptions.parse(new String[]{"run", "--config", "config.yml"});

        assertEquals(AppOptions.Command.RUN, options.command());
        assertEquals("config.yml", options.configurationLocation());
    }

    @Test
    void parsesVerifyCommandOptions() {
        AppOptions options = AppOptions.parse(
                new String[]{
                        "verify",
                        "--artifact", "custom.jar",
                        "--key", "release.asc",
                        "--key-url", "https://example.com/key.asc",
                        "--fingerprint", "AB CD",
                        "--signature", "custom.jar.asc",
                        "--signature-url", "https://example.com/custom.jar.asc",
                        "--gpg", "/usr/local/bin/gpg"
                },
                Map.of()
        );

        assertEquals(AppOptions.Command.VERIFY, options.command());
        assertEquals(Path.of("custom.jar"), options.verificationArtifact());
        assertEquals(Path.of("release.asc"), options.releasePublicKey());
        assertEquals(URI.create("https://example.com/key.asc"), options.releasePublicKeyUrl());
        assertEquals("ABCD", options.normalizedReleaseKeyFingerprint());
        assertEquals(Path.of("custom.jar.asc"), options.releaseSignature());
        assertEquals(URI.create("https://example.com/custom.jar.asc"), options.releaseSignatureUrl());
        assertEquals("/usr/local/bin/gpg", options.gpgCommand());
    }

    @Test
    void usesVerifyEnvironmentDefaultsWhenCommandLineOptionsAreAbsent() {
        AppOptions options = AppOptions.parse(
                new String[]{"verify"},
                Map.of(
                        "ORION_RELEASE_PUBLIC_KEY", "env-release.asc",
                        "ORION_RELEASE_PUBLIC_KEY_URL", "https://example.com/env-key.asc",
                        "ORION_RELEASE_KEY_FINGERPRINT", "AA BB",
                        "ORION_RELEASE_SIGNATURE", "env.jar.asc",
                        "ORION_RELEASE_SIGNATURE_URL", "https://example.com/env.jar.asc",
                        "ORION_GPG", "gpg2"
                )
        );

        assertEquals(AppOptions.Command.VERIFY, options.command());
        assertNull(options.verificationArtifact());
        assertEquals(Path.of("env-release.asc"), options.releasePublicKey());
        assertEquals(URI.create("https://example.com/env-key.asc"), options.releasePublicKeyUrl());
        assertEquals("AABB", options.normalizedReleaseKeyFingerprint());
        assertEquals(Path.of("env.jar.asc"), options.releaseSignature());
        assertEquals(URI.create("https://example.com/env.jar.asc"), options.releaseSignatureUrl());
        assertEquals("gpg2", options.gpgCommand());
    }

    @Test
    void parsesVerifyHelpOption() {
        AppOptions options = AppOptions.parse(new String[]{"verify", "--help"}, Map.of());

        assertEquals(AppOptions.Command.VERIFY, options.command());
        assertTrue(options.helpRequested());
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

    @Test
    void rejectsUnknownVerifyOption() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> AppOptions.parse(new String[]{"verify", "--config", "config.yml"}, Map.of()));

        assertEquals("Unknown verify option: --config", error.getMessage());
    }
}
