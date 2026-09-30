package pro.deta.orion.git.s3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.storage.GitStorageAccess;
import pro.deta.orion.git.parser.v2.storage.shared.PackHandle;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@Timeout(30)
class S3GitStorageTest {
    @Test
    void inventoriesOnlyStoredPackObjects() throws Exception {
        try (S3GitIndexTest.Wire wire = new S3GitIndexTest.Wire(); S3Transport transport = new S3Transport();
             S3GitStorageApi owner = new S3GitStorageApi(wire.objects(transport));
             GitStorageAccess storage = owner.createAccess()) {
            PackId stored = PackId.create();
            PackId pending = PackId.create();
            wire.contents.put("/bucket/repo/packs/" + stored + ".data", new byte[]{1});
            wire.contents.put("/bucket/repo/packs/not-a-pack.data", new byte[]{1});
            storage.newPack(pending);
            assertThat(storage.packIds()).containsExactly(stored);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedMultipartUploadAbortsAndCloseReleasesTheHandle(boolean rejectAbort) throws Exception {
        try (S3GitIndexTest.Wire wire = new S3GitIndexTest.Wire(); S3Transport transport = new S3Transport();
             S3GitStorageApi owner = new S3GitStorageApi(wire.objects(transport));
             GitStorageAccess storage = owner.createAccess()) {
            PackId id = PackId.create();
            PackHandle handle = storage.newPack(id);
            handle.write(65L * 1024 * 1024, ByteBuffer.wrap(new byte[]{42}));
            wire.rejectMultipart = true;
            wire.rejectAbort = rejectAbort;
            Throwable failure = catchThrowable(handle::flush);
            assertThat(failure).isInstanceOf(IOException.class);
            assertThat(failure.getCause().getSuppressed()).hasSize(rejectAbort ? 1 : 0);
            assertThat(wire.abortedUploads).hasValue(1);
            assertThatThrownBy(handle::close).isInstanceOf(IOException.class);
            assertThat(handle.isOpen()).isFalse();
            assertThat(wire.abortedUploads).hasValue(2);
            assertThat(storage.exists(id)).isFalse();
        }
    }

    @Test
    void failedIndexPublicationLeavesFlushedBytesAndRetryPublishesThem() throws Exception {
        try (S3GitIndexTest.Wire wire = new S3GitIndexTest.Wire(); S3Transport transport = new S3Transport()) {
            S3RepositoryObjects objects = wire.objects(transport);
            PackId id = PackId.create();
            IndexedObject object = new IndexedObject(id, new ObjectId("a".repeat(40)), GitObjectType.BLOB,
                    4, 0, 4, Optional.empty());
            PackMetadata pack = new PackMetadata(id, new PackChecksum("b".repeat(40)), id.toString(), 1, 36);
            try (S3GitStorageApi storageApi = new S3GitStorageApi(objects);
                 GitStorageAccess storage = storageApi.createAccess();
                 S3GitIndexApi index = new S3GitIndexApi(objects)) {
                try (PackHandle handle = storage.newPack(id)) {
                    handle.write(0, ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));
                    handle.flush();
                    handle.flush();
                }
                index.withAccess(Optional.of(id), access -> { access.addObject(object); return null; });
                wire.rejectIndexes = true;
                assertThatThrownBy(() -> index.withAccess(Optional.of(id), access -> access.publishIndex(pack)))
                        .isInstanceOf(IOException.class);
                index.withAccess(access -> {
                    assertThat(access.locations(object.objectId())).isEmpty();
                    return null;
                });
                try (S3GitStorageApi reopened = new S3GitStorageApi(objects);
                     GitStorageAccess bytes = reopened.createAccess()) {
                    assertThat(bytes.<byte[]>readPack(id, 0, 4, (length, input) -> input.readBytes(4)))
                            .containsExactly(1, 2, 3, 4);
                }
                wire.rejectIndexes = false;
                index.withAccess(Optional.of(id), access -> access.publishIndex(pack));
            }
            try (S3GitIndexApi reopened = new S3GitIndexApi(objects)) {
                reopened.withAccess(access -> {
                    assertThat(access.locations(object.objectId())).containsExactly(object);
                    return null;
                });
                wire.contents.put("/bucket/repo/indexes/" + id + ".index", new byte[]{1, 2, 3, 4});
                assertThatThrownBy(() -> reopened.withAccess(access -> access.packs()))
                        .isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void rangesAreBoundedBorrowedAndPropagateCallbackAndTruncationFailures() throws Exception {
        try (S3GitIndexTest.Wire wire = new S3GitIndexTest.Wire(); S3Transport transport = new S3Transport();
             S3GitStorageApi owner = new S3GitStorageApi(wire.objects(transport));
             GitStorageAccess storage = owner.createAccess()) {
            PackId id = PackId.create();
            wire.contents.put("/bucket/repo/packs/" + id + ".data", new byte[]{1, 2, 3, 4});
            AtomicReference<BufferedByteInputV2> borrowed = new AtomicReference<>();
            assertThat(storage.<byte[]>readPack(id, 1, 2, (length, input) -> {
                borrowed.set(input);
                return input.newInputStream().readAllBytes();
            })).containsExactly(2, 3);
            assertThatThrownBy(() -> borrowed.get().readUnsignedByte()).isInstanceOf(IOException.class);
            IOException expected = new IOException("reader failed");
            assertThatThrownBy(() -> storage.readPack(id, 0, 4, (length, input) -> { throw expected; }))
                    .isSameAs(expected);
            wire.truncateRange = true;
            assertThatThrownBy(() -> storage.readPack(id, 0, 4,
                    (length, input) -> input.newInputStream().readAllBytes())).isInstanceOf(IOException.class);
        }
    }
}
