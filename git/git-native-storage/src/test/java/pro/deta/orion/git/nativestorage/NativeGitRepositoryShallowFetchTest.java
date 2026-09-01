package pro.deta.orion.git.nativestorage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.upload.GitUploadPackException;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.upload.NativeFetchResponse;
import pro.deta.orion.git.nativestorage.upload.NativeObjectFilter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeGitRepositoryShallowFetchTest {
    private static final String NULL_ID = "0".repeat(40);

    @Test
    void fetchResponseCarriesShallowBoundaryMetadata() {
        LooseObjectStore objects = new LooseObjectStore();
        GitObjectId baseBlob = objects.write(
                ObjectType.BLOB,
                "base".getBytes(StandardCharsets.UTF_8));
        GitObjectId baseTree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "file.txt", baseBlob));
        GitObjectId baseCommit = writeCommit(objects, baseTree, null, "base");
        GitObjectId tipBlob = objects.write(
                ObjectType.BLOB,
                "tip".getBytes(StandardCharsets.UTF_8));
        GitObjectId tipTree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "file.txt", tipBlob));
        GitObjectId tipCommit = writeCommit(objects, tipTree, baseCommit, "tip");
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                objects,
                "refs/heads/main");

        NativeFetchResponse response = repository.fetchResponse(
                new NativeFetchRequest(
                        Set.of(tipCommit),
                        Set.of(),
                        true,
                        false,
                        false,
                        false,
                        false,
                        1));
        CompositeByteBuf pack = produce(response.packProducer());

        try {
            assertThat(response.shallowBoundaries())
                    .containsExactly(tipCommit);
            assertThat(pack.getInt(8)).isEqualTo(3);
        } finally {
            pack.release();
        }
    }

    @Test
    void shallowFetchDoesNotUseExternalThinBase() {
        LooseObjectStore objects = new LooseObjectStore();
        GitObjectId baseBlob = objects.write(
                ObjectType.BLOB,
                ("shared prefix\n".repeat(80)
                        + "base line\n"
                        + "shared suffix\n".repeat(80))
                        .getBytes(StandardCharsets.UTF_8));
        GitObjectId baseTree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "file.txt", baseBlob));
        GitObjectId baseCommit = writeCommit(objects, baseTree, null, "base");
        GitObjectId tipBlob = objects.write(
                ObjectType.BLOB,
                ("shared prefix\n".repeat(80)
                        + "tip line updated\n"
                        + "shared suffix\n".repeat(80))
                        .getBytes(StandardCharsets.UTF_8));
        GitObjectId tipTree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "file.txt", tipBlob));
        GitObjectId tipCommit = writeCommit(objects, tipTree, baseCommit, "tip");
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                objects,
                "refs/heads/main");

        byte[] pack = produceBytes(repository.fetch(
                new NativeFetchRequest(
                        Set.of(tipCommit),
                        Set.of(baseCommit),
                        true,
                        true,
                        true,
                        false,
                        false,
                        1)));

        assertThat(intAt(pack, 8)).isEqualTo(3);
        assertThat(packEntryTypes(pack)).doesNotContain(7);
    }

    @Test
    void rejectsUnsupportedTimeAndRefBasedDeepeningBeforePackBuild() {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                new LooseObjectStore(),
                "refs/heads/main");
        GitObjectId want = repository.writeObject(
                ObjectType.BLOB,
                "payload".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> repository.fetchResponse(
                new NativeFetchRequest(
                        Set.of(want),
                        Set.of(),
                        true,
                        false,
                        false,
                        false,
                        false,
                        0,
                        NativeObjectFilter.NONE,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        false,
                        1_700_000_000L,
                        Set.of("refs/heads/main"))))
                .isInstanceOfSatisfying(
                        GitUploadPackException.class,
                        error -> assertThat(error.kind())
                                .isEqualTo(
                                        GitUploadPackException.Kind
                                                .UNSUPPORTED_FEATURE));
    }

    private static CompositeByteBuf produce(
            NativePackProducer producer) {
        CompositeByteBuf complete = Unpooled.compositeBuffer();
        try (producer) {
            while (true) {
                ByteBuf fragment = Unpooled.buffer(3, 3);
                NativePackProducer.Result result;
                try {
                    result = producer.produce(fragment);
                    complete.addComponent(
                            true,
                            fragment.retain());
                } finally {
                    fragment.release();
                }
                if (result == NativePackProducer.Result.COMPLETED) {
                    return complete;
                }
            }
        } catch (RuntimeException error) {
            complete.release();
            throw error;
        }
    }

    private static byte[] produceBytes(NativePackProducer producer) {
        CompositeByteBuf pack = produce(producer);
        try {
            return ByteBufUtil.getBytes(pack);
        } finally {
            pack.release();
        }
    }

    private static GitObjectId writeCommit(
            LooseObjectStore objects,
            GitObjectId tree,
            GitObjectId parent,
            String message) {
        StringBuilder data = new StringBuilder()
                .append("tree ")
                .append(tree)
                .append('\n');
        if (parent != null) {
            data.append("parent ").append(parent).append('\n');
        }
        data.append("author Test <test@example.com> 0 +0000\n")
                .append("committer Test <test@example.com> 0 +0000\n")
                .append('\n')
                .append(message)
                .append('\n');
        return objects.write(
                ObjectType.COMMIT,
                data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] treeEntry(
            String mode,
            String name,
            GitObjectId objectId) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes((mode + " " + name + "\0")
                .getBytes(StandardCharsets.UTF_8));
        output.writeBytes(HexFormat.of().parseHex(objectId.value()));
        return output.toByteArray();
    }

    private static List<Integer> packEntryTypes(byte[] pack) {
        int count = intAt(pack, 8);
        int offset = 12;
        List<Integer> types = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            EntryHeader header = readEntryHeader(pack, offset);
            types.add(header.typeId());
            offset = header.nextOffset();
            if (header.typeId() == 7) {
                offset += 20;
            }
            offset = skipDeflated(pack, offset, pack.length - 20);
        }
        return types;
    }

    private static EntryHeader readEntryHeader(byte[] pack, int offset) {
        int currentOffset = offset;
        int current = pack[currentOffset++] & 0xff;
        int typeId = (current >>> 4) & 0x07;
        while ((current & 0x80) != 0) {
            current = pack[currentOffset++] & 0xff;
        }
        return new EntryHeader(typeId, currentOffset);
    }

    private static int skipDeflated(
            byte[] pack,
            int offset,
            int end) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(pack, offset, end - offset);
            byte[] scratch = new byte[1024];
            while (!inflater.finished()) {
                int produced = inflater.inflate(scratch);
                if (produced == 0) {
                    if (inflater.needsInput()) {
                        throw new IllegalStateException(
                                "Deflated pack entry is truncated");
                    }
                    if (inflater.needsDictionary()) {
                        throw new IllegalStateException(
                                "Deflated pack entry needs a dictionary");
                    }
                    throw new IllegalStateException(
                            "Deflated pack entry made no progress");
                }
            }
            return end - inflater.getRemaining();
        } catch (DataFormatException error) {
            throw new IllegalStateException(
                    "Invalid deflated pack entry",
                    error);
        } finally {
            inflater.end();
        }
    }

    private static int intAt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24
                | (bytes[offset + 1] & 0xff) << 16
                | (bytes[offset + 2] & 0xff) << 8
                | (bytes[offset + 3] & 0xff);
    }

    private record EntryHeader(
            int typeId,
            int nextOffset) {
    }
}
