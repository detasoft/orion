package pro.deta.orion.git.parser.v2.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pack.GitPackObjectResolver;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackIngestor;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;
import pro.deta.orion.git.parser.v2.read.HashedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.capability.GitCapabilityValue.value;

class FetchCommandPackTest implements BufferedByteInputV2.Source {
    @TempDir
    Path directory;
    private ByteBuffer source;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sendsStoredBytesWithoutRecompressionWithBothV2Framings(boolean sidebandAll) throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        byte[] content = new byte[200000];
        new Random(17).nextBytes(content);
        ObjectId id = store(storage, GitObjectType.BLOB, content);
        byte[] stored = storage.readObject(id, (type, size, base, input) ->
                input.readBytes(compressed(content).length)).orElseThrow();
        byte[] response = execute(storage, GitProtocolVersion.V2, capabilities(GitCapability.SIDEBAND_ALL),
                sidebandAll ? "sideband-all" : "no-progress", "want " + id.toHex(), "done", "FLUSH");
        byte[] pack = v2Pack(response, sidebandAll);
        assertChecksum(pack);
        try (IndexedPack indexed = ingest(pack)) {
            assertThat(indexed.objectCount()).isEqualTo(1);
            IndexedPack.EntryMetadata entry = indexed.find(id).orElseThrow();
            assertThat(Arrays.copyOfRange(pack, (int) entry.dataOffset(), pack.length - 20)).isEqualTo(stored);
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory.resolve("incoming"))) {
            assertThat(files.iterator().hasNext()).isFalse();
        }
    }

    @Test
    void excludesCommonHistoryButIncludesNewTreeAndBlob() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        ObjectId oldBlob = store(storage, GitObjectType.BLOB, new byte[]{1});
        ObjectId oldTree = store(storage, GitObjectType.TREE, tree(oldBlob));
        ObjectId oldCommit = store(storage, GitObjectType.COMMIT, commit(oldTree, Optional.empty()));
        ObjectId newBlob = store(storage, GitObjectType.BLOB, new byte[]{2});
        ObjectId newTree = store(storage, GitObjectType.TREE, tree(newBlob));
        ObjectId newCommit = store(storage, GitObjectType.COMMIT, commit(newTree, Optional.of(oldCommit)));
        byte[] response = execute(storage, GitProtocolVersion.V2, capabilities(),
                "want " + newCommit.toHex(), "have " + oldCommit.toHex(), "done", "FLUSH");
        try (IndexedPack pack = ingest(v2Pack(response, false))) {
            assertThat(pack.objectCount()).isEqualTo(3);
            for (ObjectId id : Set.of(newBlob, newTree, newCommit)) {
                assertThat(pack.find(id)).isPresent();
            }
            for (ObjectId id : Set.of(oldBlob, oldTree, oldCommit)) {
                assertThat(pack.find(id)).isEmpty();
            }
        }
    }

    @Test
    void waitForDoneKeepsTheResponseAtAcknowledgments() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        ObjectId id = store(storage, GitObjectType.BLOB, new byte[]{42});
        byte[] response = execute(storage, GitProtocolVersion.V2, capabilities(GitCapability.WAIT_FOR_DONE),
                "wait-for-done", "want " + id.toHex(), "have " + id.toHex(), "FLUSH");
        try (BufferedByteInputV2 input = input(response)) {
            assertThat(((GitPktLine.Data) GitPktLine.readNextFrom(input).orElseThrow()).text())
                    .isEqualTo("acknowledgments");
            assertThat(((GitPktLine.Data) GitPktLine.readNextFrom(input).orElseThrow()).text())
                    .isEqualTo("ACK " + id.toHex());
            assertThat(GitPktLine.readNextFrom(input)).contains(GitPktLine.Control.FLUSH);
            assertThat(GitPktLine.readNextFrom(input)).isEmpty();
        }
    }

    @Test
    void readyWithoutDoneSendsAnEmptyPackWhenEverythingIsCommon() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        ObjectId id = store(storage, GitObjectType.BLOB, new byte[]{42});
        byte[] response = execute(storage, GitProtocolVersion.V2, capabilities(),
                "want " + id.toHex(), "have " + id.toHex(), "FLUSH");
        try (IndexedPack pack = ingest(v2Pack(response, false))) {
            assertThat(pack.entryCount()).isZero();
        }
    }

    @Test
    void commonShallowCommitDoesNotImplyThatTheClientHasItsParents() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        ObjectId tree = store(storage, GitObjectType.TREE, new byte[0]);
        ObjectId parent = store(storage, GitObjectType.COMMIT, commit(tree, Optional.empty()));
        ObjectId shallow = store(storage, GitObjectType.COMMIT, commit(tree, Optional.of(parent)));
        byte[] response = execute(storage, GitProtocolVersion.V2, capabilities(GitCapability.SHALLOW),
                "want " + parent.toHex(), "have " + shallow.toHex(), "shallow " + shallow.toHex(),
                "done", "FLUSH");
        try (IndexedPack pack = ingest(v2Pack(response, false))) {
            assertThat(pack.objectCount()).isEqualTo(1);
            assertThat(pack.find(parent)).isPresent();
            assertThat(pack.find(shallow)).isEmpty();
        }
    }

    @Test
    void wantedRefsUsesUtf8AndSidebandAllButDelimitersRemainUnprefixed() throws Exception {
        GitCapabilities capabilities = capabilities(GitCapability.SIDEBAND_ALL);
        ObjectId id = new ObjectId("1".repeat(40));
        RefId ref = new RefId("refs/heads/ветка");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BufferedByteInputV2 input = input(new byte[0])) {
            GitProtocolContext.Writer writer = new GitProtocolContext(input,
                    new OutputStreamBufferedByteOutput(bytes), GitProtocolVersion.V2, GitTransport.HTTP).writer();
            writer.beginPack(capabilities, Map.of(ref, id), Map.of()).write(new byte[]{42});
            writer.endPack(capabilities);
        }
        try (BufferedByteInputV2 input = input(bytes.toByteArray())) {
            GitPktLine.Data section = (GitPktLine.Data) GitPktLine.readNextFrom(input).orElseThrow();
            assertThat(new String(section.content(), StandardCharsets.UTF_8)).isEqualTo("\1wanted-refs\n");
            GitPktLine.Data reference = (GitPktLine.Data) GitPktLine.readNextFrom(input).orElseThrow();
            assertThat(new String(reference.content(), StandardCharsets.UTF_8))
                    .isEqualTo("\1" + id.toHex() + " " + ref.value() + "\n");
            assertThat(GitPktLine.readNextFrom(input)).contains(GitPktLine.Control.DELIMITER);
            GitPktLine.Data header = (GitPktLine.Data) GitPktLine.readNextFrom(input).orElseThrow();
            assertThat(new String(header.content(), StandardCharsets.UTF_8)).isEqualTo("\1packfile\n");
            assertThat(readSideband(input, GitPktLine.MAX_PKT_LINE_LENGTH)).containsExactly(42);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "side-band", "side-band-64k"})
    void legacySendsNakThenRawOrBoundedSidebandPack(String capability) throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        byte[] content = new byte[9000];
        new Random(4).nextBytes(content);
        ObjectId id = store(storage, GitObjectType.BLOB, content);
        byte[] response = execute(storage, GitProtocolVersion.V0,
                capabilities(GitCapability.SIDE_BAND, GitCapability.SIDE_BAND_64K),
                "want " + id.toHex() + (capability.isEmpty() ? "" : " " + capability), "FLUSH", "done");
        byte[] pack;
        try (BufferedByteInputV2 input = input(response)) {
            assertThat(((GitPktLine.Data) GitPktLine.readNextFrom(input).orElseThrow()).text()).isEqualTo("NAK");
            pack = capability.isEmpty() ? input.newInputStream().readAllBytes()
                    : readSideband(input, capability.equals("side-band") ? 1000 : GitPktLine.MAX_PKT_LINE_LENGTH);
        }
        try (IndexedPack indexed = ingest(pack)) {
            assertThat(indexed.find(id)).isPresent();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"thin", "full", "base-in-pack"})
    void reusesDeltaOnlyWithAnAvailableBaseAndOtherwiseWritesAFullObject(String mode) throws Exception {
        boolean thin = mode.equals("thin");
        boolean includeBase = mode.equals("base-in-pack");
        GitStorageApi storage = new GitStorageApi(directory);
        byte[] base = {1, 2, 3};
        byte[] target = {1, 2, 4};
        byte[] delta = {3, 3, 3, 1, 2, 4};
        ObjectId baseId = hash(GitObjectType.BLOB, base);
        ObjectId targetId = hash(GitObjectType.BLOB, target);
        byte[] baseZlib = compressed(base);
        byte[] deltaZlib = compressed(delta);
        int deltaOffset = 13 + baseZlib.length;
        ByteBuffer bytes = ByteBuffer.allocate(deltaOffset + 2 + deltaZlib.length);
        bytes.putInt(0x5041434b).putInt(2).putInt(2).put((byte) 0x33).put(baseZlib);
        bytes.put((byte) 0x66).put((byte) (deltaOffset - 12)).put(deltaZlib);
        IndexedPack stored = IndexedPack.create();
        stored.append(bytes.flip());
        stored.append(ByteBuffer.wrap(MessageDigest.getInstance("SHA-1").digest(bytes.array())));
        stored.addEntry(12, 13, 3, GitObjectType.BLOB, OptionalLong.empty(), Optional.empty());
        stored.addObject(12, baseId, GitObjectType.BLOB, 3);
        stored.addEntry(deltaOffset, deltaOffset + 2, delta.length, GitObjectType.OFS_DELTA,
                OptionalLong.of(12), Optional.empty());
        stored.addObject(deltaOffset, targetId, GitObjectType.BLOB, 3);
        new GitPackObjectResolver(stored, storage).complete();
        storage.persist(stored);
        byte[] response = execute(storage, GitProtocolVersion.V2, capabilities(),
                thin ? "thin-pack" : "no-progress", "want " + targetId.toHex(),
                (includeBase ? "want " : "have ") + baseId.toHex(), "done", "FLUSH");
        byte[] pack = v2Pack(response, false);
        assertChecksum(pack);
        assertThat(ByteBuffer.wrap(pack, 8, 4).getInt()).isEqualTo(includeBase ? 2 : 1);
        if (thin || includeBase) {
            assertThat(pack[12]).isEqualTo((byte) 0x76);
            assertThat(Arrays.copyOfRange(pack, 13, 33)).isEqualTo(baseId.toBytes());
            assertThat(Arrays.copyOfRange(pack, 33, 33 + deltaZlib.length)).isEqualTo(deltaZlib);
        } else {
            try (IndexedPack indexed = ingest(pack)) {
                assertThat(indexed.find(targetId).orElseThrow().type()).isEqualTo(GitObjectType.BLOB);
                assertThat(indexed.find(baseId)).isEmpty();
            }
        }
    }

    @Test
    void sendsLegacyShallowBoundariesBeforeNegotiationAndPack() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        ObjectId tree = store(storage, GitObjectType.TREE, new byte[0]);
        ObjectId root = store(storage, GitObjectType.COMMIT, commit(tree, Optional.empty()));
        ObjectId tip = store(storage, GitObjectType.COMMIT, commit(tree, Optional.of(root)));
        byte[] response = execute(storage, GitProtocolVersion.V0, capabilities(GitCapability.SHALLOW),
                "want " + tip.toHex() + " shallow", "deepen 1", "FLUSH", "done");
        try (BufferedByteInputV2 input = input(response)) {
            assertThat(((GitPktLine.Data) GitPktLine.readNextFrom(input).orElseThrow()).text())
                    .isEqualTo("shallow " + tip.toHex());
            assertThat(GitPktLine.readNextFrom(input)).contains(GitPktLine.Control.FLUSH);
            assertThat(((GitPktLine.Data) GitPktLine.readNextFrom(input).orElseThrow()).text()).isEqualTo("NAK");
            try (IndexedPack pack = ingest(input.newInputStream().readAllBytes())) {
                assertThat(pack.find(tip)).isPresent();
                assertThat(pack.find(tree)).isPresent();
                assertThat(pack.find(root)).isEmpty();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deepensOneGenerationFromClientBoundary(boolean sidebandAll) throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        ObjectId tree = store(storage, GitObjectType.TREE, new byte[0]);
        ObjectId root = store(storage, GitObjectType.COMMIT, commit(tree, Optional.empty()));
        ObjectId parent = store(storage, GitObjectType.COMMIT, commit(tree, Optional.of(root)));
        ObjectId boundary = store(storage, GitObjectType.COMMIT, commit(tree, Optional.of(parent)));
        ObjectId tip = store(storage, GitObjectType.COMMIT, commit(tree, Optional.of(boundary)));
        byte[] response = execute(storage, GitProtocolVersion.V2,
                capabilities(GitCapability.SHALLOW, GitCapability.SIDEBAND_ALL),
                sidebandAll ? "sideband-all" : "no-progress", "want " + tip.toHex(),
                "shallow " + boundary.toHex(), "have " + boundary.toHex(),
                "deepen 1", "deepen-relative", "done", "FLUSH");
        assertThat(new String(response, StandardCharsets.ISO_8859_1))
                .contains("shallow " + parent.toHex() + "\n", "unshallow " + boundary.toHex() + "\n");
        try (IndexedPack pack = ingest(v2Pack(response, sidebandAll))) {
            assertThat(pack.find(tip)).isPresent();
            assertThat(pack.find(parent)).isPresent();
            assertThat(pack.find(root)).isEmpty();
            assertThat(pack.find(boundary)).isEmpty();
        }
    }

    @Test
    void cutsHistoryAtTimestampAndExcludedRevision() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        ObjectId tree = store(storage, GitObjectType.TREE, new byte[0]);
        ObjectId root = store(storage, GitObjectType.COMMIT, commit(tree, Optional.empty()));
        byte[] parentContent = new String(commit(tree, Optional.of(root)), StandardCharsets.US_ASCII)
                .replace(" 0 +0000", " 100 +0000").getBytes(StandardCharsets.US_ASCII);
        ObjectId parent = store(storage, GitObjectType.COMMIT, parentContent);
        byte[] tipContent = new String(commit(tree, Optional.of(parent)), StandardCharsets.US_ASCII)
                .replace(" 0 +0000", " 300 +0000").getBytes(StandardCharsets.US_ASCII);
        ObjectId tip = store(storage, GitObjectType.COMMIT, tipContent);
        byte[] response = execute(storage, GitProtocolVersion.V2, capabilities(GitCapability.SHALLOW),
                "want " + tip.toHex(), "deepen-since 200", "done", "FLUSH");
        assertThat(new String(response, StandardCharsets.ISO_8859_1)).contains("shallow " + tip.toHex());
        try (IndexedPack pack = ingest(v2Pack(response, false))) {
            assertThat(pack.find(tip)).isPresent();
            assertThat(pack.find(parent)).isEmpty();
        }
        storage.updateRefs(java.util.List.of(new pro.deta.orion.git.parser.v2.data.RefUpdate(
                new RefId("refs/heads/excluded"), Optional.empty(), Optional.of(parent))), true);
        byte[] excluded = execute(storage, GitProtocolVersion.V2, capabilities(GitCapability.SHALLOW),
                "want " + tip.toHex(), "deepen-not refs/heads/excluded", "done", "FLUSH");
        assertThat(new String(excluded, StandardCharsets.ISO_8859_1)).contains("shallow " + tip.toHex());
        try (IndexedPack pack = ingest(v2Pack(excluded, false))) {
            assertThat(pack.find(tip)).isPresent();
            assertThat(pack.find(parent)).isEmpty();
        }
    }

    @Test
    void blobFilterOmitsTreeBlobsAndRetainsExplicitlyWantedBlob() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        ObjectId blob = store(storage, GitObjectType.BLOB, new byte[]{1});
        ObjectId tree = store(storage, GitObjectType.TREE, tree(blob));
        ObjectId tip = store(storage, GitObjectType.COMMIT, commit(tree, Optional.empty()));
        byte[] response = execute(storage, GitProtocolVersion.V2, capabilities(GitCapability.FILTER),
                "want " + tip.toHex(), "filter blob:none", "done", "FLUSH");
        try (IndexedPack pack = ingest(v2Pack(response, false))) {
            assertThat(pack.find(tip)).isPresent();
            assertThat(pack.find(tree)).isPresent();
            assertThat(pack.find(blob)).isEmpty();
        }
        byte[] explicit = execute(storage, GitProtocolVersion.V2, capabilities(GitCapability.FILTER),
                "want " + blob.toHex(), "filter blob:none", "done", "FLUSH");
        try (IndexedPack pack = ingest(v2Pack(explicit, false))) {
            assertThat(pack.find(blob)).isPresent();
        }
    }

    private ObjectId store(GitStorageApi storage, GitObjectType type, byte[] content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(bytes), 1);
             BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(content))) {
            writer.writeObject(type, content.length, input);
            writer.finish();
        }
        IndexedPack pack = ingest(bytes.toByteArray());
        new GitPackObjectResolver(pack, storage).complete();
        storage.persist(pack);
        return hash(type, content);
    }

    private IndexedPack ingest(byte[] bytes) throws IOException {
        try (BufferedByteInputV2 input = input(bytes);
             PackIngestor ingestor = new PackIngestor(input, IndexedPack.create())) {
            return ingestor.ingest();
        }
    }

    private byte[] execute(GitStorageApi storage, GitProtocolVersion version,
                           GitCapabilities advertised, String... lines) throws IOException {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        OutputStreamBufferedByteOutput wire = new OutputStreamBufferedByteOutput(request);
        for (String line : lines) {
            if (line.equals("FLUSH")) {
                GitPktLine.Control.FLUSH.writeTo(wire);
            } else {
                new GitPktLine.Data((line + "\n").getBytes(StandardCharsets.US_ASCII)).writeTo(wire);
            }
        }
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        try (BufferedByteInputV2 input = input(request.toByteArray())) {
            new FetchCommand(storage, advertised).action(new GitProtocolContext(input,
                    new OutputStreamBufferedByteOutput(response), version, GitTransport.HTTP));
        }
        return response.toByteArray();
    }

    private byte[] v2Pack(byte[] response, boolean sidebandAll) throws IOException {
        try (BufferedByteInputV2 input = input(response)) {
            while (true) {
                GitPktLine packet = GitPktLine.readNextFrom(input).orElseThrow();
                if (packet instanceof GitPktLine.Data data) {
                    byte[] content = data.content();
                    int offset = sidebandAll ? 1 : 0;
                    if (sidebandAll) {
                        assertThat(content[0]).isEqualTo((byte) 1);
                    }
                    String text = new String(content, offset, content.length - offset, StandardCharsets.US_ASCII);
                    if (text.equals("packfile\n")) {
                        return readSideband(input, GitPktLine.MAX_PKT_LINE_LENGTH);
                    }
                } else {
                    assertThat(packet).isEqualTo(GitPktLine.Control.DELIMITER);
                }
            }
        }
    }

    private static byte[] readSideband(BufferedByteInputV2 input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GitPktLine packet;
        while ((packet = GitPktLine.readNextFrom(input).orElseThrow()) instanceof GitPktLine.Data data) {
            assertThat(data.length()).isLessThanOrEqualTo(limit);
            assertThat(data.content()[0]).isEqualTo((byte) 1);
            bytes.write(data.content(), 1, data.content().length - 1);
        }
        assertThat(packet).isEqualTo(GitPktLine.Control.FLUSH);
        assertThat(GitPktLine.readNextFrom(input)).isEmpty();
        return bytes.toByteArray();
    }

    private BufferedByteInputV2 input(byte[] bytes) {
        source = ByteBuffer.wrap(bytes);
        return new BufferedByteInputV2(this);
    }

    @Override
    public ByteBuffer read() {
        return source.hasRemaining() ? source : null;
    }

    @Override
    public void release() {}

    @Override
    public void close() {}

    private static GitCapabilities capabilities(GitCapability... values) {
        GitCapabilities result = new GitCapabilities();
        for (GitCapability capability : values) {
            result.add(value(capability));
        }
        return result;
    }

    private static byte[] compressed(byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream output = new DeflaterOutputStream(bytes)) {
            output.write(content);
        }
        return bytes.toByteArray();
    }

    private static ObjectId hash(GitObjectType type, byte[] content) throws IOException {
        try (BufferedByteInputV2 input = new BufferedByteInputV2(
                new ByteArrayInputStream(compressed(content)))) {
            return new HashedGitObjectRead().read(type, content.length, Optional.empty(), input);
        }
    }

    private static byte[] tree(ObjectId blob) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write("100644 file\0".getBytes(StandardCharsets.US_ASCII));
        bytes.write(blob.toBytes());
        return bytes.toByteArray();
    }

    private static byte[] commit(ObjectId tree, Optional<ObjectId> parent) {
        String text = "tree " + tree.toHex() + "\n"
                + parent.map(id -> "parent " + id.toHex() + "\n").orElse("")
                + "author A <a@example.test> 0 +0000\ncommitter A <a@example.test> 0 +0000\n\nmessage\n";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static void assertChecksum(byte[] bytes) throws Exception {
        byte[] expected = MessageDigest.getInstance("SHA-1").digest(Arrays.copyOf(bytes, bytes.length - 20));
        assertThat(Arrays.copyOfRange(bytes, bytes.length - 20, bytes.length)).isEqualTo(expected);
    }
}
