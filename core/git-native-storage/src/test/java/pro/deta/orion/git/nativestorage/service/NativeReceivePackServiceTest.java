package pro.deta.orion.git.nativestorage.service;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.pack.PackIngestor;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.capability.GitCapabilitySet;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCommand;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCommandSection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    void createsBranchOnFirstPush() {
        byte[] blobData = "hello".getBytes();
        byte[] pack = buildPackWithBlob(blobData);
        String newId = blobSha1(blobData);

        ReceivePackCommandSection section = commandSection(
                new ReceivePackCommand(NULL_ID, newId, "refs/heads/main"));

        ReceiveResult result = service.receive(section, new ByteArrayInputStream(pack));

        assertThat(result.packAccepted()).isTrue();
        assertThat(result.refResults()).hasSize(1);
        assertThat(result.refResults().get(0).ok()).isTrue();
        assertThat(result.refResults().get(0).refName()).isEqualTo("refs/heads/main");
        assertThat(refStore.read("refs/heads/main")).isPresent();
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

        ReceiveResult result = service.receive(section, new ByteArrayInputStream(pack));

        assertThat(result.packAccepted()).isTrue();
        assertThat(result.refResults().get(0).ok()).isFalse();
        assertThat(result.refResults().get(0).reason()).contains("stale");
    }

    @Test
    void leavesRefsUnchangedOnPackFailure() {
        byte[] badPack = new byte[]{1, 2, 3, 4};
        ReceivePackCommandSection section = commandSection(
                new ReceivePackCommand(NULL_ID, "a".repeat(40), "refs/heads/main"));

        ReceiveResult result = service.receive(section, new ByteArrayInputStream(badPack));

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

        ReceiveResult result = service.receive(section, new ByteArrayInputStream(emptyPack));

        assertThat(result.packAccepted()).isFalse();
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

        ReceiveResult result = service.receive(section, new ByteArrayInputStream(pack));

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

        ReceiveResult result = service.receive(section, new ByteArrayInputStream(pack));

        assertThat(result.packAccepted()).isFalse();
        assertThat(result.packError()).isEqualTo("delete commands are not supported");
    }

    @Test
    void rejectsUnsupportedAtomicCapabilityBeforeUpdatingRefs() {
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

        ReceiveResult result = service.receive(section, new ByteArrayInputStream(pack));

        assertThat(result.packAccepted()).isFalse();
        assertThat(result.packError()).isEqualTo("unsupported capabilities: atomic");
        assertThat(result.refResults()).isEmpty();
        assertThat(refStore.read("refs/heads/main")).isEmpty();
    }

    private static ReceivePackCommandSection commandSection(ReceivePackCommand... commands) {
        return new ReceivePackCommandSection(List.of(commands), new GitCapabilitySet(List.of()));
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
