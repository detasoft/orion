package pro.deta.orion.git.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionAction;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.decision.DecisionRequiredException;
import pro.deta.orion.keymaterial.ConfigurationCipherCapability;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.GitProxyBinding;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.schema.orion.RemoteAlias;
import pro.deta.orion.util.Result;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitFile;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitFileUpdate;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.schema.config.BootstrapSourceConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapGitRuntimeProxyTest {
    @Test
    void rejectedUpstreamCasDoesNotAdvanceLocalRef() throws Exception {
        BootstrapGitLocation location = fileLocation();
        NativeGitRepository repository = new InMemoryNativeGitRepositoryProvider()
                .create(location.proxyName()).valueOrFailure("create proxy");
        repository.saveFiles(
                location.refName(),
                Map.of("orion.xml", GitFile.regular("first".getBytes())), Set.of(),
                "first",
                GitCommitAuthor.EMPTY);
        String oldId = repository.refs().get(location.refName());
        NativeGitFileUpdate update = repository.prepareFileUpdate(
                location.refName(),
                Map.of("orion.xml", GitFile.regular("second".getBytes())), Set.of(),
                "second",
                GitCommitAuthor.EMPTY);
        AtomicInteger refreshes = new AtomicInteger();
        BootstrapGitRuntimeProxy proxy = new BootstrapGitRuntimeProxy(
                location,
                repository,
                new BootstrapGitTransportFactory(new BootstrapSecretResolver(Map.of()), ignored -> java.util.List.of()),
                (ignoredLocation, ignoredTransport, ignoredRepository) -> refreshes.incrementAndGet(),
                (ignoredLocation, ignoredTransport, ignoredRepository, received, updates, atomic) ->
                        java.util.Collections.nCopies(updates.size(), false));

        List<RefUpdateResult> results = proxy.publish(
                ingest(repository, update),
                update.refUpdates(),
                true);

        assertThat(refreshes).hasValue(1);
        assertThat(results).extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.EXPECTED_OLD_MISMATCH);
        assertThat(repository.refs()).containsEntry(location.refName(), oldId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void fetchAndPushFailuresReachTheSameDecisionHandlerWithoutChangingRefs(boolean failFetch) throws Exception {
        BootstrapGitLocation location = fileLocation();
        NativeGitRepository repository = new InMemoryNativeGitRepositoryProvider()
                .create(location.proxyName()).valueOrFailure("create proxy");
        repository.saveFiles(location.refName(), Map.of("file", GitFile.regular(new byte[]{1})), Set.of(),
                "first", GitCommitAuthor.EMPTY);
        String previous = repository.refs().get(location.refName());
        NativeGitFileUpdate update = repository.prepareFileUpdate(location.refName(),
                Map.of("file", GitFile.regular(new byte[]{2})), Set.of(), "second", GitCommitAuthor.EMPTY);
        GitProxyBinding binding = new GitProxyBinding(new RemoteAlias("upstream"), location.remoteUri(),
                location.refName(), location.credentialKind(), Optional.empty(), Optional.empty(), Set.of());
        OrionDocument document = new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(),
                Optional.empty(), List.of(), List.of(binding)), List.of());
        ConfigurationSecrets secrets = new ConfigurationSecrets(() -> document, ConfigurationCipherCapability.unavailable());
        IOException origin = new IOException("connection rejected");
        PrincipalAddress actor = PrincipalAddress.parse("system/operator");
        try (DecisionRegistry registry = new DecisionRegistry(1, Runnable::run, (principal, scope) -> true)) {
            Decision decision = new Decision(binding.alias(), Optional.empty(), "Confirm connection", "",
                    List.of(new DecisionAction("Trust", false, principal -> Result.of(null))));
            DecisionRequiredException required = new DecisionRequiredException(decision, origin);
            BootstrapGitTransportFactory factory = BootstrapGitTransportFactory.persistent(() -> document, secrets,
                    new pro.deta.orion.decision.ConnectionFailureHandler(registry), null);
            AtomicInteger publications = new AtomicInteger();
            BootstrapGitRuntimeProxy runtime = new BootstrapGitRuntimeProxy(location, repository, factory,
                    (selected, transport, target) -> { if (failFetch) throw required; },
                    (selected, transport, target, received, updates, atomic) -> {
                        publications.incrementAndGet();
                        throw required;
                    });
            Optional<PackId> pack = ingest(repository, update);
            assertThatThrownBy(() -> runtime.publish(pack, update.refUpdates(), true))
                    .isInstanceOf(BootstrapGitProxyException.class)
                    .satisfies(failure -> {
                        assertThat(failure.getCause()).isInstanceOf(DecisionRequiredException.class);
                        assertThat(failure.getCause().getCause()).isSameAs(required);
                    });
            assertThat(registry.list(actor)).containsExactly(decision.request());
            assertThat(publications).hasValue(failFetch ? 0 : 1);
            assertThat(repository.refs()).containsEntry(location.refName(), previous);
            assertThat(runtime.syncObservation().status())
                    .isEqualTo(ProxyAwareNativeGitRepositoryProvider.SyncStatus.UNAVAILABLE);
        }
    }

    private static BootstrapGitLocation fileLocation() {
        BootstrapSourceConfig config = new BootstrapSourceConfig();
        config.setLocation("git+file:///upstream.git?ref=main");
        config.setPath("orion.xml");
        config.setAuth(Map.of());
        return BootstrapGitLocation.parse(config);
    }

    private static Optional<PackId> ingest(NativeGitRepository repository, NativeGitFileUpdate update) throws IOException {
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(update.pack()))) {
            IndexedPack pack = repository.ingest(input);
            return Optional.of(repository.storage().persist(pack));
        }
    }
}
