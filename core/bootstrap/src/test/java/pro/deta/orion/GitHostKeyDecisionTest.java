package pro.deta.orion;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.storage.AccessControlConcurrentUpdateException;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.acl.storage.AccessControlSnapshot;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.schema.orion.ConfigurationSecret;
import pro.deta.orion.schema.orion.GitCredentialKind;
import pro.deta.orion.schema.orion.GitProxyBinding;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.schema.orion.RemoteAlias;
import pro.deta.orion.schema.orion.UserId;
import pro.deta.orion.util.Result;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

class GitHostKeyDecisionTest {
    private static final PublicKey FIRST = key();
    private static final PublicKey SECOND = key();
    private static final PrincipalAddress ACTOR = new PrincipalAddress.SystemPrincipalAddress(new UserId("admin"));

    @Test
    void addsTheApprovedKeyAfterSavingAndPreservesExistingKeysAndSecondaryFiles() throws Exception {
        Fixture fixture = new Fixture();
        byte[] secondary = fixture.storage.snapshot.files().get("secondary.xml");
        GitHostKeyDecision operation = fixture.request(SECOND);
        assertThat(operation.request().scope()).isEmpty();
        assertThat(operation.request().description())
                .contains(fixture.binding().upstream().toASCIIString(), KeyUtils.getFingerPrint(SECOND));
        assertThat(operation.request().actions()).containsKeys("add", "reject");
        assertThat(fixture.registry.list(ACTOR)).hasSize(1);
        fixture.answer(operation, "add");
        assertThat(operation.result().toCompletableFuture()).isNotDone();
        assertThat(fixture.storage.saves).isZero();
        assertThat(fixture.registry.list(ACTOR)).isEmpty();
        fixture.work.remove().run();
        GitProxyBinding saved = operation.result().toCompletableFuture().join().valueOrFailure("saved");
        assertThat(saved.knownHosts()).containsExactlyInAnyOrder(encoded(FIRST), encoded(SECOND));
        assertThat(fixture.binding()).isEqualTo(saved);
        assertThat(fixture.storage.snapshot.files().get("secondary.xml")).isEqualTo(secondary);
        OrionDocument persisted = OrionXml.read(new ByteArrayInputStream(
                fixture.storage.snapshot.files().get("orion.xml")));
        assertThat(persisted.system().proxies().getFirst()).isEqualTo(saved);
        assertThat(fixture.storage.lastRequest.message()).contains("admin");
        assertThat(fixture.registry.decide(operation.request().id(), new Decision("add", ACTOR)).isFailure())
                .isTrue();
        assertThat(fixture.storage.saves).isEqualTo(1);
    }

    @Test
    void rejectionDoesNotScheduleOrSave() throws Exception {
        Fixture fixture = new Fixture();
        GitHostKeyDecision operation = fixture.request(SECOND);
        fixture.answer(operation, "reject");
        assertThat(operation.result().toCompletableFuture().join().isFailure()).isTrue();
        assertThat(fixture.work).isEmpty();
        assertThat(fixture.storage.saves).isZero();
        assertThat(fixture.binding().knownHosts()).containsExactly(encoded(FIRST));
    }

    @Test
    void addingAnExistingKeyDoesNotDuplicateIt() throws Exception {
        Fixture fixture = new Fixture();
        GitHostKeyDecision operation = fixture.request(FIRST);
        fixture.answer(operation, "add");
        fixture.work.remove().run();
        assertThat(operation.result().toCompletableFuture().join().isFailure()).isFalse();
        assertThat(fixture.binding().knownHosts()).containsExactly(encoded(FIRST));
    }

    @Test
    void addsAnEd25519KeyUsingTheSshProvidersSerialization() throws Exception {
        Fixture fixture = new Fixture();
        PublicKey jdkKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
        PublicKey sshKey = SecurityUtils.getKeyFactory(SecurityUtils.EDDSA)
                .generatePublic(new X509EncodedKeySpec(jdkKey.getEncoded()));
        GitHostKeyDecision operation = fixture.request(sshKey);
        fixture.answer(operation, "add");
        fixture.work.remove().run();
        assertThat(operation.result().toCompletableFuture().join().isFailure()).isFalse();
        assertThat(encoded(sshKey)).startsWith("ssh-ed25519 ");
        assertThat(fixture.binding().knownHosts()).contains(encoded(sshKey), encoded(FIRST));
    }

    @Test
    void rejectsAStaleRevisionWithoutWriting() throws Exception {
        Fixture fixture = new Fixture();
        GitHostKeyDecision operation = fixture.request(SECOND);
        fixture.storage.snapshot = new AccessControlSnapshot(fixture.storage.snapshot.files(), Optional.of("other"));
        fixture.answer(operation, "add");
        fixture.work.remove().run();
        assertThat(operation.result().toCompletableFuture().join())
                .isInstanceOfSatisfying(Result.Failure.class,
                        failure -> assertThat(failure.message()).isEqualTo("Connection configuration changed"));
        assertThat(fixture.storage.saves).isZero();
    }

    @Test
    void failedPersistenceDoesNotReportTrustOrChangePublishedConfiguration() throws Exception {
        Fixture fixture = new Fixture();
        GitHostKeyDecision operation = fixture.request(SECOND);
        fixture.storage.failSave = true;
        fixture.answer(operation, "add");
        fixture.work.remove().run();
        assertThat(operation.result().toCompletableFuture().join().isFailure()).isTrue();
        assertThat(fixture.binding().knownHosts()).containsExactly(encoded(FIRST));
    }

    @Test
    void cancellationAndRegistryShutdownPreventSaving() throws Exception {
        Fixture fixture = new Fixture();
        GitHostKeyDecision cancelled = fixture.request(SECOND);
        assertThat(cancelled.cancel()).isTrue();
        assertThat(cancelled.result().toCompletableFuture().join().isFailure()).isTrue();
        GitHostKeyDecision stopped = fixture.request(SECOND);
        fixture.registry.close();
        assertThat(stopped.result().toCompletableFuture().join().isFailure()).isTrue();
        assertThat(fixture.work).isEmpty();
        assertThat(fixture.storage.saves).isZero();
    }

    @Test
    void reportsExecutorRejectionWithoutLeavingAnUnfinishedResult() throws Exception {
        Fixture fixture = new Fixture();
        GitHostKeyDecision operation = fixture.request(SECOND, command -> {
            throw new RejectedExecutionException("stopped");
        });
        fixture.answer(operation, "add");
        assertThat(operation.result().toCompletableFuture().join().isFailure()).isTrue();
        assertThat(fixture.storage.saves).isZero();
    }

    @Test
    void acceptedDecisionCannotBeCancelledWhileItsSaveIsQueued() throws Exception {
        Fixture fixture = new Fixture();
        GitHostKeyDecision operation = fixture.request(SECOND);
        fixture.answer(operation, "add");
        assertThat(operation.cancel()).isFalse();
        fixture.work.remove().run();
        assertThat(operation.result().toCompletableFuture().join().isFailure()).isFalse();
    }

    @Test
    void propagatesRegistryCapacityFailureWithoutCreatingAnotherRequest() throws Exception {
        Fixture fixture = new Fixture();
        fixture.request(SECOND);
        assertThat(GitHostKeyDecision.request(fixture.registry, fixture.configuration, fixture.work::add,
                fixture.desired.current(), fixture.binding(), SECOND).isFailure()).isTrue();
        assertThat(fixture.registry.list(ACTOR)).hasSize(1);
        assertThat(fixture.storage.saves).isZero();
    }

    private static PublicKey key() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(256);
            return generator.generateKeyPair().getPublic();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String encoded(PublicKey key) {
        return PublicKeyEntry.toString(key);
    }

    @Test
    void unsupportedKeyDoesNotCreateARequest() throws Exception {
        Fixture fixture = new Fixture();
        PublicKey unsupported = new PublicKey() {
            @Override public String getAlgorithm() { return "unsupported-test-key"; }
            @Override public String getFormat() { return "RAW"; }
            @Override public byte[] getEncoded() { return new byte[]{1}; }
        };
        assertThat(GitHostKeyDecision.request(fixture.registry, fixture.configuration, fixture.work::add,
                fixture.desired.current(), fixture.binding(), unsupported).isFailure()).isTrue();
        assertThat(fixture.registry.list(ACTOR)).isEmpty();
    }

    @Test
    void refusesABindingThatWasNotUsedByTheObservedConfiguration() throws Exception {
        Fixture fixture = new Fixture();
        GitProxyBinding original = fixture.binding();
        GitProxyBinding different = new GitProxyBinding(original.alias(), URI.create("ssh://git@other.test/repo"),
                original.ref(), original.credentialKind(), original.secret(), original.username(), original.knownHosts());
        assertThat(GitHostKeyDecision.request(fixture.registry, fixture.configuration, fixture.work::add,
                fixture.desired.current(), different, SECOND).isFailure()).isTrue();
        assertThat(fixture.registry.list(ACTOR)).isEmpty();
    }

    private static final class Fixture {
        final MemoryStorage storage = new MemoryStorage();
        final OrionDesiredState desired = new OrionDesiredState();
        final DecisionRegistry registry = new DecisionRegistry(1, (actor, scope) -> true);
        final ArrayDeque<Runnable> work = new ArrayDeque<>();
        final OrionAccessControlServiceImpl configuration = new OrionAccessControlServiceImpl(storage,
                new OrionPasswordHashingService(), null, OrionRuntimeOptions.defaults(),
                ServerIdentityCapability.unavailable(), desired);

        Fixture() throws IOException {
            configuration.reload("fixture");
        }

        GitProxyBinding binding() {
            return desired.current().document().system().proxies().getFirst();
        }

        GitHostKeyDecision request(PublicKey key) {
            return request(key, work::add);
        }

        GitHostKeyDecision request(PublicKey key, Executor executor) {
            return GitHostKeyDecision.request(registry, configuration, executor, desired.current(), binding(), key)
                    .valueOrFailure("request");
        }

        void answer(GitHostKeyDecision operation, String action) {
            registry.decide(operation.request().id(), new Decision(action, ACTOR)).valueOrFailure("answer");
        }
    }

    private static final class MemoryStorage implements AccessControlStorage {
        AccessControlSnapshot snapshot;
        AccessControlSaveRequest lastRequest;
        int saves;
        boolean failSave;

        MemoryStorage() throws IOException {
            GitProxyBinding binding = new GitProxyBinding(new RemoteAlias("cluster"),
                    URI.create("ssh://git@example.test/repo"), "main", GitCredentialKind.PASSWORD,
                    Optional.of("password"), Optional.empty(), Set.of(encoded(FIRST)));
            OrionDocument document = new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(),
                    Optional.empty(), List.of(new ConfigurationSecret("password", "opaque")), List.of(binding)),
                    List.of());
            ByteArrayOutputStream primary = new ByteArrayOutputStream();
            ByteArrayOutputStream secondary = new ByteArrayOutputStream();
            OrionXml.write(document, primary);
            OrionXml.write(OrionDocument.withAccessControl(new AccessControl()), secondary);
            snapshot = new AccessControlSnapshot(Map.of("orion.xml", primary.toByteArray(),
                    "secondary.xml", secondary.toByteArray()), Optional.of("0"));
        }

        @Override
        public Result<AccessControlSnapshot> load() {
            return Result.of(snapshot);
        }

        @Override
        public String primaryPath() {
            return "orion.xml";
        }

        @Override
        public void save(AccessControlSnapshot candidate, AccessControlSaveRequest request) {
            if (failSave) throw new IllegalStateException("storage unavailable");
            if (!snapshot.version().equals(candidate.version())) {
                throw new AccessControlConcurrentUpdateException("conflict", null);
            }
            lastRequest = request;
            snapshot = new AccessControlSnapshot(candidate.files(), Optional.of(Integer.toString(++saves)));
        }
    }
}
