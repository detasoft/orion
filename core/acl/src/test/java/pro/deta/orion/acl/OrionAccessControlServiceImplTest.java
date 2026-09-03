package pro.deta.orion.acl;

import org.junit.jupiter.api.Test;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.acl.storage.AccessControlSnapshot;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.util.OrionProvider;
import pro.deta.orion.util.Result;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionAccessControlServiceImplTest {
    private static final String ACL_PATH = "config/orion.xml";

    @Test
    void resetFailsWithoutPrintingWhenThePersistedAclCannotBeReloaded() throws Exception {
        assertRecoveryFailsWithoutPrinting(defaultAclSnapshot(), new OrionRuntimeOptions(true));
    }

    @Test
    void defaultCreationFailsWithoutPrintingWhenThePersistedAclCannotBeReloaded() throws Exception {
        assertRecoveryFailsWithoutPrinting(null, OrionRuntimeOptions.defaults());
    }

    private static void assertRecoveryFailsWithoutPrinting(
            AccessControlSnapshot initial,
            OrionRuntimeOptions runtimeOptions) {
        FailingReloadStorage storage = new FailingReloadStorage(initial);
        OrionEventManager eventManager = new OrionEventManager();
        OrionProvider provider = new OrionProvider(() -> null, () -> eventManager, () -> null);
        OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(
                storage,
                new OrionPasswordHashingService(),
                provider,
                new OrionConfiguration(),
                runtimeOptions,
                ServerIdentityCapability.unavailable());
        ByteArrayOutputStream processOutput = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        eventManager.onStart();
        try {
            System.setOut(new PrintStream(processOutput, true, StandardCharsets.UTF_8));

            assertThatThrownBy(service::onStart)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Configuration repository not initialized");
        } finally {
            System.setOut(originalOut);
            service.onStop();
            eventManager.onStop();
        }

        assertThat(storage.saved).isTrue();
        assertThat(processOutput.toString(StandardCharsets.UTF_8)).doesNotContain("---ROOT PASSWORD: ");
    }

    private static AccessControlSnapshot defaultAclSnapshot() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new XmlService().serialize(ACLUtil.generateDefaultAccessControl("old-password-hash"), output);
        return new AccessControlSnapshot(Map.of(ACL_PATH, output.toByteArray()), Optional.of("initial"));
    }

    private static final class FailingReloadStorage implements AccessControlStorage {
        private final AccessControlSnapshot initial;
        private boolean saved;

        private FailingReloadStorage(AccessControlSnapshot initial) {
            this.initial = initial;
        }

        @Override
        public Result<AccessControlSnapshot> load() {
            if (saved) {
                return new Result.Failure<>(
                        Result.FailureCode.GENERAL,
                        "simulated reload failure",
                        new IOException("simulated reload failure"));
            }
            if (initial == null) {
                return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
            }
            return new Result.Success<>(initial);
        }

        @Override
        public void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request) {
            saved = true;
        }

        @Override
        public String primaryPath() {
            return ACL_PATH;
        }
    }
}
