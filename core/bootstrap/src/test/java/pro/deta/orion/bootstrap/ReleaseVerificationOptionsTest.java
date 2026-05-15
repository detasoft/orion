package pro.deta.orion.bootstrap;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReleaseVerificationOptionsTest {

    @Test
    void parsesCommandLineOptions() {
        ReleaseVerificationOptions options = ReleaseVerificationOptions.parse(
                new String[]{
                        "--artifact", "custom.jar",
                        "--key", "release.asc",
                        "--key-url", "https://example.com/key.asc",
                        "--fingerprint", "AB CD",
                        "--signature", "custom.jar.asc",
                        "--signature-url", "https://example.com/custom.jar.asc",
                        "--gpg", "/usr/local/bin/gpg"
                },
                Map.of(),
                Path.of("default.jar")
        );

        assertEquals(Path.of("custom.jar"), options.artifact());
        assertEquals(Path.of("release.asc"), options.publicKey());
        assertEquals(URI.create("https://example.com/key.asc"), options.publicKeyUrl());
        assertEquals("ABCD", options.normalizedFingerprint());
        assertEquals(Path.of("custom.jar.asc"), options.signature());
        assertEquals(URI.create("https://example.com/custom.jar.asc"), options.signatureUrl());
        assertEquals("/usr/local/bin/gpg", options.gpgCommand());
    }

    @Test
    void usesEnvironmentDefaultsWhenCommandLineOptionsAreAbsent() {
        ReleaseVerificationOptions options = ReleaseVerificationOptions.parse(
                new String[0],
                Map.of(
                        "ORION_RELEASE_PUBLIC_KEY", "env-release.asc",
                        "ORION_RELEASE_PUBLIC_KEY_URL", "https://example.com/env-key.asc",
                        "ORION_RELEASE_KEY_FINGERPRINT", "AA BB",
                        "ORION_RELEASE_SIGNATURE", "env.jar.asc",
                        "ORION_RELEASE_SIGNATURE_URL", "https://example.com/env.jar.asc",
                        "ORION_GPG", "gpg2"
                ),
                Path.of("default.jar")
        );

        assertEquals(Path.of("default.jar"), options.artifact());
        assertEquals(Path.of("env-release.asc"), options.publicKey());
        assertEquals(URI.create("https://example.com/env-key.asc"), options.publicKeyUrl());
        assertEquals("AABB", options.normalizedFingerprint());
        assertEquals(Path.of("env.jar.asc"), options.signature());
        assertEquals(URI.create("https://example.com/env.jar.asc"), options.signatureUrl());
        assertEquals("gpg2", options.gpgCommand());
    }
}
