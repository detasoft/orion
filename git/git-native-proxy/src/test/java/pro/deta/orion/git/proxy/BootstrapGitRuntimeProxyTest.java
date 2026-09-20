package pro.deta.orion.git.proxy;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapGitRuntimeProxyTest {
    @Test
    void rejectedUpstreamCasDoesNotAdvanceLocalRef() throws Exception {
        BootstrapGitLocation location = fileLocation();
        NativeGitRepository repository = new InMemoryNativeGitRepositoryProvider()
                .create(location.proxyName()).valueOrFailure("create proxy");
        repository.saveFiles(
                location.refName(),
                Map.of("orion.xml", "first".getBytes()),
                "first",
                GitCommitAuthor.EMPTY);
        String oldId = repository.refs().get(location.refName());
        NativeGitFileUpdate update = repository.prepareFileUpdate(
                location.refName(),
                Map.of("orion.xml", "second".getBytes()),
                "second",
                GitCommitAuthor.EMPTY);
        AtomicInteger refreshes = new AtomicInteger();
        BootstrapGitRuntimeProxy proxy = new BootstrapGitRuntimeProxy(
                location,
                repository,
                new BootstrapGitTransportFactory(new BootstrapSecretResolver(Map.of())),
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
