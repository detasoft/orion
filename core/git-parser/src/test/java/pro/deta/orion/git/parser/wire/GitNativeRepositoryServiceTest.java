package pro.deta.orion.git.parser.wire;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GitNativeRepositoryServiceTest {
    private static final String NULL_ID = "0".repeat(40);
    private static final String MAIN_ID = "1".repeat(40);
    private static final String TAG_ID = "2".repeat(40);

    @Test
    void createsEmptyRepositoryAndAdvertisesCapabilityPseudoRef() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitV1Advertisement advertisement =
                service.legacyUploadPackAdvertisement(
                        request("/demo.git"));

        assertThat(provider.exists("demo.git")).isTrue();
        assertThat(advertisement.refs()).containsExactly(
                GitAdvertisedRef.direct(
                        NULL_ID,
                        "capabilities^{}"));
    }

    @Test
    void advertisesHeadFirstAndSortsRepositoryRefs() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.findOrCreate("demo.git")
                        .valueOrFailure("repository");
        repository.updateRef(
                "refs/tags/v1",
                NULL_ID,
                TAG_ID);
        repository.updateRef(
                "refs/heads/main",
                NULL_ID,
                MAIN_ID);
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitV1Advertisement advertisement =
                service.legacyUploadPackAdvertisement(
                        request("/demo.git"));

        assertThat(advertisement.refs()).containsExactly(
                GitAdvertisedRef.direct(MAIN_ID, "HEAD"),
                GitAdvertisedRef.direct(
                        MAIN_ID,
                        "refs/heads/main"),
                GitAdvertisedRef.direct(
                        TAG_ID,
                        "refs/tags/v1"));
        assertThat(advertisement.capabilities())
                .extracting(capability -> capability.wireToken())
                .contains("symref=HEAD:refs/heads/main");
    }

    private static InitialRequestData request(String path) {
        return new InitialRequestData(
                InitialRequestService.UPLOAD_PACK,
                path,
                "localhost",
                Map.of());
    }
}
