package pro.deta.orion.git.nativestorage.service;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.common.GitRefUpdateResult;
import pro.deta.orion.git.common.GitRefUpdateType;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.pack.PackIngestor;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.capability.GitCapabilitySet;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCommand;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCommandSection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class NativeReceivePackServiceTest {
    private static final String NULL_ID = "0".repeat(40);
    private static final long MAX_PACK = 10 * 1024 * 1024L;

    private final LooseRefStore refStore = new LooseRefStore();
    private final LooseObjectStore objectStore = new LooseObjectStore();
    private final PackIngestor packIngestor = new PackIngestor(MAX_PACK);
    private final NativeReceivePackService service =
            new NativeReceivePackService(refStore, objectStore, packIngestor);

    @Test
    void advertisesSortedRefsAndImplementedCapabilities() {
        refStore.update("refs/heads/z", NULL_ID, "b".repeat(40));
        refStore.update("refs/tags/v1.0", NULL_ID, "c".repeat(40));
        refStore.update("refs/heads/a", NULL_ID, "a".repeat(40));
        refStore.update("refs/internal/hidden", NULL_ID, "d".repeat(40));

        List<ByteBuf> packets = service.advertise();
        try {
            assertThat(payloadOf(packets.get(0))).startsWith("a".repeat(40) + " refs/heads/a\0");
            assertThat(payloadOf(packets.get(0))).contains("report-status");
            assertThat(payloadOf(packets.get(0))).contains("side-band-64k");
            assertThat(payloadOf(packets.get(0))).contains("ofs-delta");
            assertThat(payloadOf(packets.get(0))).contains("atomic");
            assertThat(payloadOf(packets.get(0))).contains("object-format=sha1");
            assertThat(payloadOf(packets.get(1))).isEqualTo("b".repeat(40) + " refs/heads/z");
            assertThat(payloadOf(packets.get(2))).isEqualTo("c".repeat(40) + " refs/tags/v1.0");
            assertThat(packets).hasSize(4);
        } finally {
            release(packets);
        }
    }

    @Test
    void createsBranchOnFirstPush() {
        byte[] blobData = "hello".getBytes();
        byte[] pack = buildPackWithBlob(blobData);
        String newId = blobSha1(blobData);

        ReceivePackCommandSection section = commandSection(
                new ReceivePackCommand(NULL_ID, newId, "refs/heads/main"));

        ReceiveResult result = receive(section, pack);

        assertThat(result.packAccepted()).isTrue();
        assertThat(result.refResults()).hasSize(1);
        assertThat(result.refResults().get(0).ok()).isTrue();
        assertThat(result.refResults().get(0).refName()).isEqualTo("refs/heads/main");
        assertThat(refStore.read("refs/heads/main")).isPresent();
        assertThat(result.refUpdates()).hasSize(1);
        assertThat(result.refUpdates().get(0).type()).isEqualTo(GitRefUpdateType.CREATE);
        assertThat(result.refUpdates().get(0).result()).isEqualTo(GitRefUpdateResult.OK);
    }

    @Test
    void rejectsStaleUpdateWhenRefHasChanged() {
        byte[] blob1 = "v1".getBytes();
        byte[] blob2 = "v2".getBytes();
        String id1 = blobSha1(blob1);
        String id2 = blobSha1(blob2);

        refStore.update("refs/heads/main", NULL_ID, id1);
        objectStore.write(ObjectType.BLOB, blob1);

        byte[] pack = buildPackWithBlob(blob2);
        ReceivePackCommandSection section = commandSection(
                new ReceivePackCommand("b".repeat(40), id2, "refs/heads/main"));

        ReceiveResult result = receive(section, pack);

        assertThat(result.packAccepted()).isTrue();
        assertThat(result.refResults().get(0).ok()).isFalse();
        assertThat(result.refResults().get(0).reason()).contains("stale");
    }

    @Test
    void leavesRefsUnchangedOnPackFailure() {
        byte[] badPack = new byte[]{1, 2, 3, 4};
        ReceivePackCommandSection section = commandSection(
                new ReceivePackCommand(NULL_ID, "a".repeat(40), "refs/heads/main"));

        ReceiveResult result = receive(section, badPack);

        assertThat(result.packAccepted()).isFalse();
        assertThat(result.packError()).isNotBlank();
        assertThat(refStore.read("refs/heads/main")).isEmpty();
    }

    @Test
    void rejectsPushWhenObjectMissingFromPack() {
        byte[] emptyPack = buildPackWithBlob(new byte[]{});
        String missingId = "d".repeat(40);
        ReceivePackCommandSection section = commandSection(
                new ReceivePackCommand(NULL_ID, missingId, "refs/heads/main"));

        ReceiveResult result = receive(section, emptyPack);

        assertThat(result.packAccepted()).isTrue();
        assertThat(result.refResults()).hasSize(1);
        assertThat(result.refResults().get(0).ok()).isFalse();
        assertThat(result.refResults().get(0).reason()).contains("missing object");
        assertThat(refStore.read("refs/heads/main")).isEmpty();
    }

    @Test
    void leavesNewObjectsQuarantinedWhenEveryRefUpdateIsStale() {
        byte[] existing = "existing".getBytes();
        byte[] incoming = "incoming".getBytes();
        String existingId = blobSha1(existing);
        String incomingId = blobSha1(incoming);

        objectStore.write(ObjectType.BLOB, existing);
        refStore.update("refs/heads/main", NULL_ID, existingId);

        byte[] pack = buildPackWithBlob(incoming);
        ReceivePackCommandSection section = commandSection(
                new ReceivePackCommand("b".repeat(40), incomingId, "refs/heads/main"));

        ReceiveResult result = receive(section, pack);

        assertThat(result.packAccepted()).isTrue();
        assertThat(result.refResults().get(0).ok()).isFalse();
        assertThat(objectStore.contains(GitObjectId.of(incomingId))).isFalse();
        assertThat(refStore.read("refs/heads/main").map(id -> id.value()))
                .hasValue(existingId);
    }

    @Test
    void explicitlyRejectsDeleteCommand() {
        byte[] pack = buildPackWithBlob("data".getBytes());
        ReceivePackCommandSection section = commandSection(
                new ReceivePackCommand("a".repeat(40), NULL_ID, "refs/heads/main"));

        ReceiveResult result = receive(section, pack);

        assertThat(result.packAccepted()).isTrue();
        assertThat(result.refResults().get(0).ok()).isFalse();
        assertThat(result.refResults().get(0).reason()).contains("branch deletes");
    }

    @Test
    void rejectsWholeAtomicTransactionWhenCasUpdateIsStale() {
        byte[] blob1 = "one".getBytes();
        byte[] blob2 = "two".getBytes();
        String id1 = blobSha1(blob1);
        String id2 = blobSha1(blob2);

        objectStore.write(ObjectType.BLOB, blob2);
        refStore.update("refs/heads/feature", NULL_ID, "f".repeat(40));

        byte[] pack = buildPackWithBlob(blob1);
        ReceivePackCommandSection section = new ReceivePackCommandSection(
                List.of(
                        new ReceivePackCommand(NULL_ID, id1, "refs/heads/main"),
                        new ReceivePackCommand("e".repeat(40), id2, "refs/heads/feature")),
                new GitCapabilitySet(List.of(GitCapability.bare("atomic"))));

        ReceiveResult result = receive(section, pack);

        assertThat(result.packAccepted()).isTrue();
        assertThat(result.refResults()).hasSize(2);
        assertThat(result.refResults().get(0).ok()).isFalse();
        assertThat(result.refResults().get(0).reason()).contains("atomic transaction failed");
        assertThat(result.refResults().get(1).ok()).isFalse();
        assertThat(result.refResults().get(1).reason()).contains("stale");
        assertThat(refStore.read("refs/heads/main")).isEmpty();
        assertThat(refStore.read("refs/heads/feature").map(GitObjectId::value)).hasValue("f".repeat(40));
        assertThat(objectStore.contains(GitObjectId.of(id1))).isFalse();
    }

    @Test
    void rejectsProtectedRefBeforePackIngestion() {
        NativeReceivePackService protectedService = serviceWithPolicy(
                ReceivePackPolicy.conservative().withProtectedRefs(Set.of("refs/heads/main")));
        byte[] blobData = "protected".getBytes();
        String newId = blobSha1(blobData);
        ReceivePackCommandSection section = commandSection(
                new ReceivePackCommand(NULL_ID, newId, "refs/heads/main"));

        ReceiveResult result = receive(protectedService, section, buildPackWithBlob(blobData));

        assertThat(result.packAccepted()).isTrue();
        assertThat(result.refResults().get(0).ok()).isFalse();
        assertThat(result.refResults().get(0).reason()).contains("protected");
        assertThat(result.refUpdates().get(0).result()).isEqualTo(GitRefUpdateResult.REJECTED_OTHER_REASON);
        assertThat(refStore.read("refs/heads/main")).isEmpty();
    }

    @Test
    void deletesBranchWhenPolicyAllowsIt() {
        NativeReceivePackService deletingService = serviceWithPolicy(
                ReceivePackPolicy.conservative().withBranchDeletes(true));
        byte[] existing = "delete me".getBytes();
        String existingId = blobSha1(existing);
        objectStore.write(ObjectType.BLOB, existing);
        refStore.update("refs/heads/main", NULL_ID, existingId);
        ReceivePackCommandSection section = new ReceivePackCommandSection(
                List.of(new ReceivePackCommand(existingId, NULL_ID, "refs/heads/main")),
                new GitCapabilitySet(List.of(GitCapability.bare("delete-refs"))));

        ReceiveResult result = receive(deletingService, section, new byte[0]);

        assertThat(result.packAccepted()).isTrue();
        assertThat(result.refResults().get(0).ok()).isTrue();
        assertThat(result.refUpdates().get(0).type()).isEqualTo(GitRefUpdateType.DELETE);
        assertThat(result.refUpdates().get(0).result()).isEqualTo(GitRefUpdateResult.OK);
        assertThat(refStore.read("refs/heads/main")).isEmpty();
    }

    @Test
    void createsTagButRejectsTagUpdateByDefault() {
        byte[] tagTarget = "tag target".getBytes();
        String tagTargetId = blobSha1(tagTarget);
        ReceivePackCommandSection createSection = commandSection(
                new ReceivePackCommand(NULL_ID, tagTargetId, "refs/tags/v1.0"));

        ReceiveResult create = receive(createSection, buildPackWithBlob(tagTarget));

        assertThat(create.refResults().get(0).ok()).isTrue();
        assertThat(refStore.read("refs/tags/v1.0")).isPresent();

        byte[] nextTarget = "next tag target".getBytes();
        String nextTargetId = blobSha1(nextTarget);
        ReceivePackCommandSection updateSection = commandSection(
                new ReceivePackCommand(tagTargetId, nextTargetId, "refs/tags/v1.0"));

        ReceiveResult update = receive(updateSection, buildPackWithBlob(nextTarget));

        assertThat(update.packAccepted()).isTrue();
        assertThat(update.refResults().get(0).ok()).isFalse();
        assertThat(update.refResults().get(0).reason()).contains("tag update");
        assertThat(refStore.read("refs/tags/v1.0").map(GitObjectId::value)).hasValue(tagTargetId);
    }

    @Test
    void acceptsFastForwardCommitUpdateAndRejectsNonFastForwardByDefault() {
        String treeId = "1".repeat(40);
        GitObjectId oldCommit = objectStore.write(ObjectType.COMMIT, commitBytes(treeId));
        GitObjectId fastForwardCommit = objectStore.write(ObjectType.COMMIT, commitBytes(treeId, oldCommit.value()));
        GitObjectId unrelatedCommit = objectStore.write(ObjectType.COMMIT, commitBytes(treeId));
        refStore.update("refs/heads/main", NULL_ID, oldCommit.value());

        ReceiveResult fastForward = receive(
                commandSection(new ReceivePackCommand(oldCommit.value(), fastForwardCommit.value(), "refs/heads/main")),
                buildPackWithBlob(new byte[]{}));

        assertThat(fastForward.refResults().get(0).ok()).isTrue();
        assertThat(refStore.read("refs/heads/main").map(GitObjectId::value)).hasValue(fastForwardCommit.value());

        ReceiveResult nonFastForward = receive(
                commandSection(new ReceivePackCommand(fastForwardCommit.value(), unrelatedCommit.value(), "refs/heads/main")),
                buildPackWithBlob(new byte[]{}));

        assertThat(nonFastForward.packAccepted()).isTrue();
        assertThat(nonFastForward.refResults().get(0).ok()).isFalse();
        assertThat(nonFastForward.refResults().get(0).reason()).contains("non-fast-forward");
        assertThat(nonFastForward.refUpdates().get(0).type()).isEqualTo(GitRefUpdateType.UPDATE_NON_FAST_FORWARD);
        assertThat(nonFastForward.refUpdates().get(0).result())
                .isEqualTo(GitRefUpdateResult.REJECTED_NON_FAST_FORWARD);
        assertThat(refStore.read("refs/heads/main").map(GitObjectId::value)).hasValue(fastForwardCommit.value());
    }

    @Test
    void acceptsNonFastForwardCommitUpdateWhenPolicyAllowsIt() {
        NativeReceivePackService forceService = serviceWithPolicy(
                ReceivePackPolicy.conservative().withNonFastForwardUpdates(true));
        String treeId = "1".repeat(40);
        GitObjectId oldCommit = objectStore.write(ObjectType.COMMIT, commitBytes(treeId));
        GitObjectId unrelatedCommit = objectStore.write(ObjectType.COMMIT, commitBytes(treeId));
        refStore.update("refs/heads/main", NULL_ID, oldCommit.value());

        ReceiveResult result = receive(
                forceService,
                commandSection(new ReceivePackCommand(oldCommit.value(), unrelatedCommit.value(), "refs/heads/main")),
                buildPackWithBlob(new byte[]{}));

        assertThat(result.refResults().get(0).ok()).isTrue();
        assertThat(result.refUpdates().get(0).type()).isEqualTo(GitRefUpdateType.UPDATE);
        assertThat(result.refUpdates().get(0).result()).isEqualTo(GitRefUpdateResult.OK);
        assertThat(refStore.read("refs/heads/main").map(GitObjectId::value)).hasValue(unrelatedCommit.value());
    }

    @Test
    void writesReportStatusThroughSideBandWhenNegotiated() {
        byte[] blobData = "status".getBytes();
        String newId = blobSha1(blobData);
        ReceivePackCommandSection section = new ReceivePackCommandSection(
                List.of(new ReceivePackCommand(NULL_ID, newId, "refs/heads/main")),
                new GitCapabilitySet(List.of(
                        GitCapability.bare("report-status"),
                        GitCapability.bare("side-band-64k"))));
        ReceiveResult result = receive(section, buildPackWithBlob(blobData));

        List<ByteBuf> packets = service.reportStatus(section, result);
        try {
            assertThat(packets).hasSize(2);
            assertThat(packets.get(0).getByte(4)).isEqualTo((byte) 1);
            assertThat(ascii(packets.get(0))).contains("unpack ok");
            assertThat(ascii(packets.get(0))).contains("ok refs/heads/main");
            assertThat(packets.get(1).readableBytes()).isEqualTo(4);
        } finally {
            release(packets);
        }
    }

    private static ReceivePackCommandSection commandSection(ReceivePackCommand... commands) {
        return new ReceivePackCommandSection(List.of(commands), new GitCapabilitySet(List.of()));
    }

    private NativeReceivePackService serviceWithPolicy(ReceivePackPolicy policy) {
        return new NativeReceivePackService(
                refStore,
                objectStore,
                packIngestor,
                policy,
                UnpooledByteBufAllocator.DEFAULT);
    }

    private ReceiveResult receive(ReceivePackCommandSection section, byte[] pack) {
        return receive(service, section, pack);
    }

    private static ReceiveResult receive(
            NativeReceivePackService service,
            ReceivePackCommandSection section,
            byte[] pack) {
        ByteBuf buffer = Unpooled.wrappedBuffer(pack);
        try {
            return service.receive(section, buffer);
        } finally {
            buffer.release();
        }
    }

    private static String payloadOf(ByteBuf packet) {
        String pktLine = ascii(packet);
        String payload = pktLine.substring(4);
        if (payload.endsWith("\n")) {
            payload = payload.substring(0, payload.length() - 1);
        }
        return payload;
    }

    private static String ascii(ByteBuf packet) {
        byte[] bytes = new byte[packet.readableBytes()];
        packet.getBytes(packet.readerIndex(), bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static void release(List<ByteBuf> packets) {
        for (ByteBuf packet : packets) {
            packet.release();
        }
    }

    private static byte[] commitBytes(String treeId, String... parents) {
        StringBuilder commit = new StringBuilder();
        commit.append("tree ").append(treeId).append('\n');
        for (String parent : parents) {
            commit.append("parent ").append(parent).append('\n');
        }
        commit.append("author Test <test@example.com> 0 +0000\n");
        commit.append("committer Test <test@example.com> 0 +0000\n");
        commit.append('\n');
        commit.append("commit\n");
        return commit.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] buildPackWithBlob(byte[] blobData) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeInt(body, 0x5041434b);
            writeInt(body, 2);
            writeInt(body, blobData.length == 0 ? 0 : 1);
            if (blobData.length > 0) {
                writePackObject(body, 3, blobData);
            }
            byte[] bodyBytes = body.toByteArray();
            byte[] checksum = sha1(bodyBytes);
            ByteArrayOutputStream pack = new ByteArrayOutputStream();
            pack.write(bodyBytes);
            pack.write(checksum);
            return pack.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String blobSha1(byte[] data) {
        byte[] header = ("blob " + data.length + "\0").getBytes();
        byte[] full = new byte[header.length + data.length];
        System.arraycopy(header, 0, full, 0, header.length);
        System.arraycopy(data, 0, full, header.length, data.length);
        byte[] hash = sha1(full);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }

    private static void writePackObject(ByteArrayOutputStream out, int typeId, byte[] data) throws IOException {
        long size = data.length;
        int firstByte = (typeId << 4) | (int) (size & 0x0f);
        size >>= 4;
        if (size > 0) {
            firstByte |= 0x80;
        }
        out.write(firstByte);
        while (size > 0) {
            int b = (int) (size & 0x7f);
            size >>= 7;
            if (size > 0) b |= 0x80;
            out.write(b);
        }
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(deflated)) {
            d.write(data);
        }
        out.write(deflated.toByteArray());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
