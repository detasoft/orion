package pro.deta.orion.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.test.integration.OrionTestRootAccess;
import pro.deta.orion.util.KeyUtils;

import java.nio.file.Path;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionTestRootEnrollmentIT {
    @TempDir
    Path tempDir;

    @Test
    void enrollsOnceWithoutSshAndReusesTheSavedKeyAfterRestart() throws Exception {
        BootstrapConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(
                tempDir.resolve("orion"));
        KeyPair key = KeyUtils.generateRSAKeyPair().valueOrFailure("test root key");

        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            TestBearerTokens.enrollRootKey(orion.accessControlService(), orion.component().configurationEditor(), key);
            assertAdminTokenWorks(orion, key);
        }
        try (RuntimeHttpTestSupport.StartedOrion restarted = RuntimeHttpTestSupport.start(configuration)) {
            assertAdminTokenWorks(restarted, key);
            KeyPair unknown = KeyUtils.generateRSAKeyPair().valueOrFailure("unknown test key");
            assertThatThrownBy(() -> OrionTestRootAccess.issueToken(
                    restarted.accessControlService(), unknown.getPublic(), 600))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Test root key authentication failed");
        }
    }

    private static void assertAdminTokenWorks(RuntimeHttpTestSupport.StartedOrion orion, KeyPair key)
            throws Exception {
        OrionAccessControlService accessControl = orion.accessControlService();
        String token = OrionTestRootAccess.issueToken(accessControl, key.getPublic(), 600);
        RuntimeHttpTestSupport.HttpResponse response = RuntimeHttpTestSupport.request(
                "GET",
                orion.httpUrl("/api/admin/acl"),
                TestBearerTokens.bearer(token));
        assertThat(response.status()).isEqualTo(200);
    }
}
