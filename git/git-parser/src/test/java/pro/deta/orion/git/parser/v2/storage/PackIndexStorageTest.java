package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.GitIndexApi;
import pro.deta.orion.git.local.LocalGitIndex;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.pack.PackIngestor;
import pro.deta.orion.git.parser.v2.read.GitPackRead;
import pro.deta.orion.git.parser.v2.storage.local.LocalGitStorage;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;
import pro.deta.orion.git.parser.v2.storage.shared.PackHandle;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.pack.PackTestData.*;

class PackIndexStorageTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void indexesFullObjectsBeforeTrailerButNeverIndexesUnresolvedDeltas(boolean memory) throws Exception {
        byte[] full = blob(new byte[]{1});
        ObjectId base = objectId(GitObjectType.BLOB, new byte[]{1});
        ObjectId target = objectId(GitObjectType.BLOB, new byte[]{2});
        byte[] bytes = pack(full, delta(base, new byte[]{1, 1, 1, 2}));
        bytes[bytes.length - 1] ^= 1;
        AtomicReference<PackId> packId = new AtomicReference<>();
        {
            {
                GitStorageAccess backend = memory ? new InMemoryStorage().createAccess()
                        : new LocalGitStorage(directory).createAccess();
                try {
                GitIndexApi owner = memory ? new InMemoryIndex() : new LocalGitIndex(directory);
                GitIndexAccess index = owner.createAccess(Optional.of(PackId.create()));
                try {
                            GitStorageAccess recording = new GitStorageAccess() {
                                public PackHandle newPack(PackId id) throws IOException {
                                    packId.set(id);
                                    return backend.newPack(id);
                            }
                                public <R> R readPack(PackId id, long offset, long length, GitPackRead<R> reader) throws IOException {
                                    return backend.readPack(id, offset, length, reader);
                            }
                                public boolean exists(PackId id) throws IOException { return backend.exists(id); }
                                public Set<PackId> packIds() throws IOException { return backend.packIds(); }
                                public void apply() throws IOException { backend.apply(); }
                                public void discard() throws IOException { backend.discard(); }
                        };
                            BufferedByteInputV2.Source source = new BufferedByteInputV2.Source() {
                                private int position;
                                public ByteBuffer read() throws IOException {
                                    if (position == bytes.length - 20) {
                                        assertThat(index.findObject(packId.get(), base)).isPresent();
                                        assertThat(index.findObject(packId.get(), target)).isEmpty();
                                        assertThat(index.locations(base)).isEmpty();
                                        assertThat(index.packs()).isEmpty();
                                }
                                    return position == bytes.length ? null : ByteBuffer.wrap(bytes, position++, 1);
                            }
                                public void release() {}
                                public void close() {}
                        };
                            try (BufferedByteInputV2 input = new BufferedByteInputV2(source);
                                 PackIngestor ingestor = new PackIngestor(input, recording, index)) {
                                assertThatThrownBy(ingestor::ingest).isInstanceOf(IOException.class)
                                        .hasMessage("Pack checksum mismatch");
                        }
                            assertThat(index.objects(packId.get())).hasSize(1);
                            assertThat(index.locations(base)).isEmpty();
                            assertThat(backend.exists(packId.get())).isTrue();
                            publish(pack(full), backend, owner);
                            assertThat(index.locations(base)).hasSize(1);
                            assertThat(index.locations(base).getFirst().packId()).isNotEqualTo(packId.get());
                    } finally {
                        index.discard();
                    }
                } finally {
                    backend.discard();
                }
            }
        }
    }
}
