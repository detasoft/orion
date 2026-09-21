package pro.deta.orion.git.parser.v2.fetch;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FetchPackTest {
    @TempDir
    Path directory;

    @Test
    void readsCommonHistoryOnlyOnceAndKeepsItsChildUnshallowed() throws Exception {
        try (GitStorageApi storage = new GitStorageApi(directory)) {
            ObjectId tree = PackTestData.store(storage, GitObjectType.TREE, new byte[0]);
            byte[] rootBytes = commit(tree);
            ObjectId root = PackTestData.store(storage, GitObjectType.COMMIT, rootBytes);
            byte[] tipBytes = commit(tree, root);
            ObjectId tip = PackTestData.store(storage, GitObjectType.COMMIT, tipBytes);
            PackId rootPack = storage.findPacksByObjectIds(List.of(root)).get(root).getFirst();
            String hex = rootPack.toHex();
            Path rootPath = directory.resolve("packs").resolve(hex.substring(0, 2))
                    .resolve(hex.substring(2) + ".pack");
            FetchPlan commonOnly = plan(Set.of(), Set.of(root), Set.of(), OptionalInt.empty());
            FetchPlan wanted = plan(Set.of(tip), Set.of(root), Set.of(), OptionalInt.empty());
            long commonReads = contentReads(storage, commonOnly, rootPath, "common.jfr");
            assertThat(commonReads).isPositive();
            assertThat(contentReads(storage, wanted, rootPath, "wanted.jfr")).isEqualTo(commonReads);
            FetchPack pack = FetchPack.prepare(storage, wanted);
            assertThat(pack.shallowCommits()).isEmpty();
            assertThat(pack.unshallowCommits()).isEmpty();
            assertThat(pack.objectCount()).isEqualTo(1);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(output), pack.objectCount())) {
                pack.writeTo(writer);
                writer.finish();
            }
            assertThat(output.toByteArray()).isEqualTo(
                    PackTestData.pack(PackTestData.entry(GitObjectType.COMMIT, tipBytes)));
        }
    }

    @Test
    void preservesShallowBoundaryAndDeepensThroughCommonCommits() throws Exception {
        try (GitStorageApi storage = new GitStorageApi()) {
            ObjectId tree = PackTestData.store(storage, GitObjectType.TREE, new byte[0]);
            ObjectId root = PackTestData.store(storage, GitObjectType.COMMIT, commit(tree));
            ObjectId boundary = PackTestData.store(storage, GitObjectType.COMMIT, commit(tree, root));
            ObjectId common = PackTestData.store(storage, GitObjectType.COMMIT, commit(tree, boundary));
            ObjectId tip = PackTestData.store(storage, GitObjectType.COMMIT, commit(tree, common));
            FetchPack ordinary = FetchPack.prepare(storage,
                    plan(Set.of(tip), Set.of(common), Set.of(boundary), OptionalInt.empty()));
            assertThat(ordinary.objectCount()).isEqualTo(1);
            assertThat(ordinary.shallowCommits()).isEmpty();
            assertThat(ordinary.unshallowCommits()).isEmpty();
            FetchPack deepened = FetchPack.prepare(storage,
                    plan(Set.of(tip), Set.of(common), Set.of(boundary), OptionalInt.of(4)));
            assertThat(deepened.objectCount()).isEqualTo(2);
            assertThat(deepened.shallowCommits()).isEmpty();
            assertThat(deepened.unshallowCommits()).containsExactly(boundary);
            FetchPack shallow = FetchPack.prepare(storage,
                    plan(Set.of(tip), Set.of(common), Set.of(tip), OptionalInt.empty()));
            assertThat(shallow.objectCount()).isEqualTo(1);
            assertThat(shallow.shallowCommits()).containsExactly(tip);
            assertThat(shallow.unshallowCommits()).isEmpty();
        }
    }

    private long contentReads(GitStorageApi storage, FetchPlan plan, Path pack, String recordingName)
            throws Exception {
        Path recordingPath = directory.resolve(recordingName);
        try (Recording recording = new Recording()) {
            recording.enable("jdk.FileRead").withThreshold(Duration.ZERO).withStackTrace();
            recording.start();
            FetchPack.prepare(storage, plan);
            recording.stop();
            recording.dump(recordingPath);
        }
        long count = 0;
        String packPath = pack.toRealPath().toString();
        for (RecordedEvent event : RecordingFile.readAllEvents(recordingPath)) {
            if (!event.getEventType().getName().equals("jdk.FileRead")
                    || !event.getString("path").equals(packPath)) {
                continue;
            }
            for (RecordedFrame frame : event.getStackTrace().getFrames()) {
                if (frame.getMethod().getType().getName()
                        .equals("pro.deta.orion.git.parser.v2.pack.PackByteSource")) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private static FetchPlan plan(Set<ObjectId> wanted, Set<ObjectId> common,
                                  Set<ObjectId> shallow, OptionalInt depth) {
        return new FetchPlan(wanted, Map.of(), common, shallow, depth, OptionalLong.empty(), Set.of(),
                Optional.empty(), new GitCapabilities(), Set.of());
    }

    private static byte[] commit(ObjectId tree, ObjectId... parents) {
        StringBuilder text = new StringBuilder("tree " + tree.toHex() + "\n");
        for (ObjectId parent : parents) {
            text.append("parent ").append(parent.toHex()).append('\n');
        }
        return text.append("author A <a@example.com> 0 +0000\ncommitter A <a@example.com> 0 +0000\n\ncommit\n")
                .toString().getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void writesPreparedEntriesInOrderWithoutReopeningTheirIndexes() throws Exception {
        try (GitStorageApi storage = new GitStorageApi(directory)) {
            byte[] first = {1, 2, 3};
            byte[] second = {4, 5};
            ObjectId firstId = PackTestData.store(storage, GitObjectType.BLOB, first);
            ObjectId secondId = PackTestData.store(storage, GitObjectType.BLOB, second);
            FetchPlan plan = new FetchPlan(new LinkedHashSet<>(List.of(secondId, firstId)), Map.of(),
                    Set.of(), Set.of(), OptionalInt.empty(), OptionalLong.empty(), Set.of(), Optional.empty(),
                    new GitCapabilities(), Set.of());
            FetchPack pack = FetchPack.prepare(storage, plan);
            assertThat(pack.objectCount()).isEqualTo(2);
            for (PackId id : storage.packIds()) {
                String hex = id.toHex();
                Path index = directory.resolve("packs").resolve(hex.substring(0, 2))
                        .resolve(hex.substring(2) + ".mv");
                Files.move(index, index.resolveSibling(index.getFileName() + ".saved"));
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(output), pack.objectCount())) {
                pack.writeTo(writer);
                writer.finish();
            }
            assertThat(output.toByteArray()).isEqualTo(
                    PackTestData.pack(PackTestData.blob(second), PackTestData.blob(first)));
        }
    }
}
