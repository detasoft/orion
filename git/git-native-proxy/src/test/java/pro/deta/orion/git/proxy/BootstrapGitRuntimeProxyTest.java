package pro.deta.orion.git.proxy;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitFileUpdate;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.schema.config.BootstrapSourceConfig;

import java.util.List;
import java.util.Map;
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
                (ignoredLocation, ignoredTransport, ignoredRepository, updates, atomic) ->
                        java.util.Collections.nCopies(updates.size(), false));

        List<RefUpdateResult> results = proxy.publish(
                update.objects(),
                update.refUpdates(),
                true);

        assertThat(refreshes).hasValue(1);
        assertThat(results).containsExactly(RefUpdateResult.STALE);
        assertThat(repository.refs()).containsEntry(location.refName(), oldId);
    }

    private static BootstrapGitLocation fileLocation() {
        BootstrapSourceConfig config = new BootstrapSourceConfig();
        config.setLocation("git+file:///upstream.git?ref=main");
        config.setPath("orion.xml");
        config.setAuth(Map.of());
        return BootstrapGitLocation.parse(config);
    }
}
