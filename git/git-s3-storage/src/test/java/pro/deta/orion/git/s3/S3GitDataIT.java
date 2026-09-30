package pro.deta.orion.git.s3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.storage.shared.PackHandle;
import pro.deta.orion.git.parser.v2.storage.GitStorageAccess;
import pro.deta.orion.test.integration.s3.MinioS3TestServer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(120)
class S3GitDataIT {
    @Test
    void packBytesAndPublishedObjectsSurviveFreshProviderWithoutLocalState() throws Exception {
        try (MinioS3TestServer server = MinioS3TestServer.start("orion-data-" + UUID.randomUUID())) {
            PackId orphan = PackId.create();
            ObjectId id;
            try (S3NativeGitRepositoryProvider provider = provider(server);
                 NativeGitRepository repository = provider.create("repo").valueOrFailure("create");
                 GitStorageAccess storage = repository.storage().createAccess()) {
                assertThat(repository.refs()).isEmpty();
                try (PackHandle writer = storage.newPack(orphan)) {
                    writer.write(0, ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));
                    assertThat(storage.<byte[]>readPack(orphan, 1, 2,
                            (length, input) -> input.readBytes(2))).containsExactly(2, 3);
                    writer.truncate(3);
                    writer.flush();
                }
                id = repository.writeObject(GitObjectType.BLOB, new byte[]{8, 9, 10});
                assertThat(repository.readObject(id)).isPresent();
            }
            try (S3NativeGitRepositoryProvider provider = provider(server);
                 NativeGitRepository repository = provider.find("repo").valueOrFailure("reopen");
                 GitStorageAccess storage = repository.storage().createAccess()) {
                assertThat(storage.<byte[]>readPack(orphan, 0, 3,
                        (length, input) -> input.readBytes(3))).containsExactly(1, 2, 3);
                Optional<PackMetadata> unpublished = repository.index().withAccess(index -> index.findPack(orphan));
                assertThat(unpublished).isEmpty();
                assertThat(repository.readObject(id).orElseThrow().data()).containsExactly(8, 9, 10);
                assertThatThrownBy(() -> storage.readPack(orphan, 0, 4, (length, input) -> 1))
                        .isInstanceOf(IOException.class);
                assertThatThrownBy(() -> storage.newPack(orphan)).isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void appliesAllChangedRefsAtomicallyAcrossIndependentProvidersAndMergesOtherRefs() throws Exception {
        try (MinioS3TestServer server = MinioS3TestServer.start("orion-refs-" + UUID.randomUUID());
             S3NativeGitRepositoryProvider one = provider(server);
             S3NativeGitRepositoryProvider two = provider(server);
             NativeGitRepository first = one.create("repo").valueOrFailure("create");
             NativeGitRepository second = two.find("repo").valueOrFailure("open")) {
            RefId main = new RefId("refs/heads/main");
            RefId other = new RefId("refs/heads/other");
            ObjectId a = first.writeObject(GitObjectType.BLOB, new byte[]{1});
            ObjectId b = second.writeObject(GitObjectType.BLOB, new byte[]{2});
            GitIndexAccess left = first.index().createAccess(List.of(new RefUpdate(main, Optional.empty(),
                    Optional.of(a))));
            GitIndexAccess right = second.index().createAccess(List.of(new RefUpdate(other, Optional.empty(),
                    Optional.of(b))));
            left.apply();
            right.apply();
            assertThat(first.refs()).containsEntry(main.value(), a.toHex()).containsEntry(other.value(), b.toHex());
            GitIndexAccess stale = first.index().createAccess(List.of(
                    new RefUpdate(main, Optional.of(a), Optional.of(b)),
                    new RefUpdate(other, Optional.of(b), Optional.empty())));
            second.index().withAccess(List.of(new RefUpdate(main, Optional.of(a), Optional.empty())), index -> {
                index.apply();
                return null;
            });
            assertThatThrownBy(stale::apply).isInstanceOf(GitRefConflictException.class);
            assertThat(second.refs()).containsExactlyEntriesOf(Map.of(other.value(), b.toHex()));
            try (S3NativeGitRepositoryProvider reopened = provider(server);
                 NativeGitRepository persisted = reopened.find("repo").valueOrFailure("reopen after conflict")) {
                assertThat(persisted.readObject(a).orElseThrow().data()).containsExactly(1);
                assertThat(persisted.readObject(b).orElseThrow().data()).containsExactly(2);
                persisted.index().withAccess(index -> {
                    assertThat(index.packs()).hasSize(2);
                    return null;
                });
            }
            GitIndexAccess surviving = first.index().createAccess(Set.of(main));
            first.index().close();
            surviving.apply();
            assertThatThrownBy(first.index()::createAccess).isInstanceOf(IOException.class);
        }
    }

    @Test
    void uploadsLargeStagingFilesInPartsAndReadsTheirTailAfterReopen() throws Exception {
        try (MinioS3TestServer server = MinioS3TestServer.start("orion-multipart-" + UUID.randomUUID());
             S3NativeGitRepositoryProvider provider = provider(server);
             NativeGitRepository repository = provider.create("repo").valueOrFailure("create")) {
            PackId id = PackId.create();
            long offset = 65L * 1024 * 1024;
            try (GitStorageAccess storage = repository.storage().createAccess();
                 PackHandle handle = storage.newPack(id)) {
                handle.write(offset, ByteBuffer.wrap(new byte[]{42}));
                handle.flush();
            }
            try (S3NativeGitRepositoryProvider reopened = provider(server);
                 NativeGitRepository other = reopened.find("repo").valueOrFailure("reopen");
                 GitStorageAccess storage = other.storage().createAccess()) {
                assertThat(storage.<byte[]>readPack(id, offset - 1, 2, (length, input) -> input.readBytes(2)))
                        .containsExactly(0, 42);
            }
        }
    }

    @Test
    void existingAccessSeesPublicationThroughAnotherHandleOfTheSameProvider() throws Exception {
        try (MinioS3TestServer server = MinioS3TestServer.start("orion-publication-" + UUID.randomUUID());
             S3NativeGitRepositoryProvider first = provider(server);
             NativeGitRepository writer = first.create("repo").valueOrFailure("create");
             NativeGitRepository reader = first.find("repo").valueOrFailure("open")) {
            reader.index().withAccess(access -> {
                assertThat(access.packs()).isEmpty();
                ObjectId id = writer.writeObject(GitObjectType.BLOB, new byte[]{7});
                assertThat(access.locations(id)).hasSize(1);
                assertThat(writer.writeObject(GitObjectType.BLOB, new byte[]{7})).isEqualTo(id);
                assertThat(access.locations(id)).hasSize(2);
                assertThat(access.packs()).hasSize(2);
                return null;
            });
        }
    }

    private static S3NativeGitRepositoryProvider provider(MinioS3TestServer server) {
        return new S3NativeGitRepositoryProvider("s3://" + server.bucketName() + "/repos", server.endpoint(),
                Map.of("accessKeyId", server.accessKeyId(), "secretAccessKey", "env:SECRET"),
                Map.of("SECRET", server.secretAccessKey()));
    }
}
