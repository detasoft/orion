package pro.deta.orion.git.nativestorage.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.pack.LocalPackPublicationStore;
import pro.deta.orion.git.nativestorage.pack.PackPublicationRequest;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PublishedPackfileUriSourceTest {
    @TempDir
    private Path tempDir;

    @Test
    void selectsSelfContainedPublishedPackCoveringRequestedObjects() {
        LocalPackPublicationStore store =
                new LocalPackPublicationStore(tempDir);
        GitObjectId objectId = GitObjectId.of("1".repeat(40));
        store.publish(request("a".repeat(40), List.of(objectId), Set.of()));
        NativeGitRepository repository = repository(store);

        NativePackfileUriSelection selection = new PublishedPackfileUriSource(
                repository,
                packId -> NativePackfileUriBuilder.packUri(
                        "https://git.example/r",
                        repository.name(),
                        packId))
                .select(Set.of(objectId), Set.of("https"));

        assertThat(selection.objectIds()).containsExactly(objectId);
        assertThat(selection.packfileUris())
                .singleElement()
                .satisfies(uri -> {
                    assertThat(uri.packHash()).isEqualTo("a".repeat(40));
                    assertThat(uri.uri()).isEqualTo(
                            "https://git.example/r/team/project.git"
                                    + "/objects/pack/"
                                    + "a".repeat(40)
                                    + ".pack");
                });
    }

    @Test
    void skipsThinPublishedPackAndUnsupportedProtocol() {
        LocalPackPublicationStore store =
                new LocalPackPublicationStore(tempDir);
        GitObjectId thinObject = GitObjectId.of("2".repeat(40));
        GitObjectId baseObject = GitObjectId.of("3".repeat(40));
        GitObjectId httpOnlyObject = GitObjectId.of("4".repeat(40));
        store.publish(request(
                "b".repeat(40),
                List.of(thinObject),
                Set.of(baseObject)));
        store.publish(request(
                "c".repeat(40),
                List.of(httpOnlyObject),
                Set.of()));
        NativeGitRepository repository = repository(store);
        PublishedPackfileUriSource source = new PublishedPackfileUriSource(
                repository,
                packId -> NativePackfileUriBuilder.packUri(
                        "http://git.example/r",
                        repository.name(),
                        packId));

        NativePackfileUriSelection selection = source.select(
                Set.of(thinObject, httpOnlyObject),
                Set.of("https"));

        assertThat(selection.objectIds()).isEmpty();
        assertThat(selection.packfileUris()).isEmpty();
    }

    private static NativeGitRepository repository(
            LocalPackPublicationStore store) {
        return new NativeGitRepository(
                "team/project.git",
                new LooseRefStore(),
                new LooseObjectStore(),
                "refs/heads/main",
                store);
    }

    private static PackPublicationRequest request(
            String packId,
            List<GitObjectId> objectIds,
            Set<GitObjectId> externalBaseIds) {
        return new PackPublicationRequest(
                ("pack-" + packId).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                ("index-" + packId).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                packId,
                "f".repeat(40),
                objectIds.size(),
                objectIds,
                externalBaseIds);
    }
}
