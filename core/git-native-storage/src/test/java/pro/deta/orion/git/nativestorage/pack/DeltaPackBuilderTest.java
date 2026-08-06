package pro.deta.orion.git.nativestorage.pack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.transport.PackParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class DeltaPackBuilderTest {
    private static final int REF_DELTA_TYPE = 7;

    private final LooseObjectStore objects = new LooseObjectStore();
    private final DeltaPackBuilder builder = new DeltaPackBuilder();

    @Test
    void buildsNonThinReferenceDeltaPackReadableByOrionJGitAndGit(
            @TempDir Path temporaryDirectory)
            throws Exception {
        BlobPair pair = similarBlobPair();
        CompositeByteBuf pack = produce(
                builder.producer(
                        objects,
                        List.of(pair.baseId(), pair.targetId())),
                5);

        try {
            byte[] packBytes = ByteBufUtil.getBytes(pack);

            assertThat(packEntryTypes(packBytes))
                    .containsExactly(
                            ObjectType.BLOB.packTypeId(),
                            REF_DELTA_TYPE);
            assertThat(ingest(packBytes).read(pair.targetId()).orElseThrow()
                    .data())
                    .isEqualTo(pair.targetData());
            assertJGitReads(packBytes, pair);
            assertGitIndexPackAccepts(packBytes, temporaryDirectory);
        } finally {
            pack.release();
        }
    }

    @Test
    void fallsBackToWholeObjectEntryWhenGeneratedDeltaIsNotUseful() {
        GitObjectId first = objects.write(
                ObjectType.BLOB,
                "a".getBytes(StandardCharsets.UTF_8));
        GitObjectId second = objects.write(
                ObjectType.BLOB,
                "z".getBytes(StandardCharsets.UTF_8));
        CompositeByteBuf pack = produce(
                builder.producer(objects, List.of(first, second)),
                3);

        try {
            assertThat(packEntryTypes(ByteBufUtil.getBytes(pack)))
                    .containsExactly(
                            ObjectType.BLOB.packTypeId(),
                            ObjectType.BLOB.packTypeId());
        } finally {
            pack.release();
        }
    }

    private BlobPair similarBlobPair() {
        byte[] base = ("shared prefix\n".repeat(80)
                + "base line\n"
                + "shared suffix\n".repeat(80))
                .getBytes(StandardCharsets.UTF_8);
        byte[] target = ("shared prefix\n".repeat(80)
                + "target line updated\n"
                + "shared suffix\n".repeat(80))
                .getBytes(StandardCharsets.UTF_8);
        return new BlobPair(
                objects.write(ObjectType.BLOB, base),
                objects.write(ObjectType.BLOB, target),
                target);
    }

    private static CompositeByteBuf produce(
            NativePackProducer producer,
            int fragmentSize) {
        CompositeByteBuf complete = Unpooled.compositeBuffer();
        try (producer) {
            while (true) {
                ByteBuf fragment = Unpooled.buffer(
                        fragmentSize,
                        fragmentSize);
                try {
                    NativePackProducer.Result result =
                            producer.produce(fragment);
                    complete.addComponent(true, fragment.retain());
                    if (result == NativePackProducer.Result.COMPLETED) {
                        return complete;
                    }
                } finally {
                    fragment.release();
                }
            }
        } catch (RuntimeException error) {
            complete.release();
            throw error;
        }
    }

    private static LooseObjectStore ingest(byte[] pack) {
        ByteBuf input = Unpooled.wrappedBuffer(pack);
        try {
            return new PackIngestor(pack.length).ingest(input);
        } finally {
            input.release();
        }
    }

    private static void assertJGitReads(
            byte[] packBytes,
            BlobPair pair) throws Exception {
        try (InMemoryRepository repository =
                     new InMemoryRepository(
                             new DfsRepositoryDescription("delta-pack-test"));
             ObjectInserter inserter = repository.newObjectInserter()) {
            PackParser parser = inserter.newPackParser(
                    new ByteArrayInputStream(packBytes));
            parser.setAllowThin(false);
            parser.parse(NullProgressMonitor.INSTANCE);
            inserter.flush();
            try (ObjectReader reader = repository.newObjectReader()) {
                ObjectLoader loader = reader.open(
                        ObjectId.fromString(pair.targetId().value()),
                        Constants.OBJ_BLOB);
                assertThat(loader.getCachedBytes())
                        .isEqualTo(pair.targetData());
            }
        }
    }

    private static void assertGitIndexPackAccepts(
            byte[] packBytes,
            Path temporaryDirectory) throws Exception {
        Path packFile = temporaryDirectory.resolve("generated.pack");
        Files.write(packFile, packBytes);
        Process process = new ProcessBuilder(
                "git",
                "index-pack",
                "--strict",
                packFile.toString())
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        assertThat(process.waitFor())
                .as(new String(output, StandardCharsets.UTF_8))
                .isEqualTo(0);
    }

    private static List<Integer> packEntryTypes(byte[] pack) {
        int count = intAt(pack, 8);
        int offset = 12;
        List<Integer> types = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            EntryHeader header = readEntryHeader(pack, offset);
            types.add(header.typeId());
            offset = header.nextOffset();
            if (header.typeId() == REF_DELTA_TYPE) {
                offset += 20;
            }
            offset = skipDeflated(pack, offset, pack.length - 20);
        }
        return types;
    }

    private static EntryHeader readEntryHeader(
            byte[] pack,
            int offset) {
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

    private static int intAt(
            byte[] bytes,
            int offset) {
        return (bytes[offset] & 0xff) << 24
                | (bytes[offset + 1] & 0xff) << 16
                | (bytes[offset + 2] & 0xff) << 8
                | (bytes[offset + 3] & 0xff);
    }

    private record EntryHeader(
            int typeId,
            int nextOffset) {
    }

    private record BlobPair(
            GitObjectId baseId,
            GitObjectId targetId,
            byte[] targetData) {
    }
}
