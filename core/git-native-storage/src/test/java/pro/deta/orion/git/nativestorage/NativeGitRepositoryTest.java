package pro.deta.orion.git.nativestorage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.common.GitFetchAccessRequest;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.common.GitReceiveRequest;
import pro.deta.orion.git.common.GitRefUpdate;
import pro.deta.orion.git.common.GitRefUpdateResult;
import pro.deta.orion.git.common.GitRefUpdateType;
import pro.deta.orion.git.common.GitUploadRequest;
import pro.deta.orion.git.common.GitUploadStats;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class NativeGitRepositoryTest {
    private static final String NULL_ID = "0".repeat(40);

    private final LooseRefStore refs = new LooseRefStore();
    private final LooseObjectStore objects = new LooseObjectStore();
    private final GitPktLineWriter writer = new GitPktLineWriter(UnpooledByteBufAllocator.DEFAULT);

    @Test
    void uploadDelegatesToNativeServiceAndPropagatesFetchAccess() throws Exception {
        GitObjectId commit = writeSingleFileCommit();
        refs.update("refs/heads/main", NULL_ID, commit.value());
        NativeGitRepository repository = new NativeGitRepository(
                "project",
                "Native test repository",
                refs,
                objects,
                Optional.of("refs/heads/main"));
        AtomicReference<GitFetchAccessRequest> accessRequest = new AtomicReference<>();
        List<GitUploadStats> uploads = new ArrayList<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        repository.withFetchAccessCheck(accessRequest::set).upload(
                new GitUploadRequest(0, Set.of("version=2"), uploads::add),
                new ByteArrayInputStream(fetchRequest(commit)),
                output,
                new ByteArrayOutputStream());

        String response = output.toString(StandardCharsets.ISO_8859_1);
        assertThat(response).contains("version 2\n");
        assertThat(response).contains("packfile\n");
        assertThat(uploads).singleElement().satisfies(stats -> {
            assertThat(stats.totalObjects()).isEqualTo(3);
            assertThat(stats.packBytes()).isGreaterThan(32);
        });
        assertThat(accessRequest.get().repositoryName()).isEqualTo("project");
        assertThat(accessRequest.get().wants()).containsExactly(commit);
        assertThat(accessRequest.get().refResolver().resolveBranchNames(List.of(commit)))
                .containsEntry(commit, "main");
    }

    @Test
    void receiveDelegatesToNativeServiceAndPublishesReceiveEvents() throws Exception {
        NativeGitRepository repository = new NativeGitRepository(
                "project",
                "Native receive test repository",
                refs,
                objects,
                Optional.of("refs/heads/main"));
        byte[] blobData = "pushed\n".getBytes(StandardCharsets.UTF_8);
        String blobId = blobSha1(blobData);
        byte[] input = receiveRequest(
                NULL_ID + " " + blobId + " refs/heads/main\0report-status side-band-64k",
                buildPackWithBlob(blobData));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<GitRefUpdate> updates = new ArrayList<>();

        repository.receive(
                new GitReceiveRequest(0, updates::addAll),
                new ByteArrayInputStream(input),
                output,
                new ByteArrayOutputStream());

        assertThat(refs.read("refs/heads/main").map(GitObjectId::value)).hasValue(blobId);
        assertThat(updates).singleElement().satisfies(update -> {
            assertThat(update.refName()).isEqualTo("refs/heads/main");
            assertThat(update.type()).isEqualTo(GitRefUpdateType.CREATE);
            assertThat(update.result()).isEqualTo(GitRefUpdateResult.OK);
        });
        String response = output.toString(StandardCharsets.ISO_8859_1);
        assertThat(response).contains("capabilities^{}");
        assertThat(response).contains("unpack ok");
        assertThat(response).contains("ok refs/heads/main");
    }

    private byte[] fetchRequest(GitObjectId commit) {
        return request(
                data("command=fetch"),
                delimiter(),
                data("thin-pack"),
                data("ofs-delta"),
                data("want " + commit.value()),
                data("done"),
                flush());
    }

    private byte[] request(ByteBuf... packets) {
        ByteBuf input = Unpooled.buffer();
        for (ByteBuf packet : packets) {
            try {
                input.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
            } finally {
                packet.release();
            }
        }
        try {
            byte[] bytes = new byte[input.readableBytes()];
            input.getBytes(input.readerIndex(), bytes);
            return bytes;
        } finally {
            input.release();
        }
    }

    private byte[] receiveRequest(String commandLine, byte[] pack) {
        byte[] command = request(data(commandLine), flush());
        byte[] result = new byte[command.length + pack.length];
        System.arraycopy(command, 0, result, 0, command.length);
        System.arraycopy(pack, 0, result, command.length, pack.length);
        return result;
    }

    private ByteBuf data(String line) {
        return writer.writeTextLine(line);
    }

    private ByteBuf delimiter() {
        return writer.writeDelimiter();
    }

    private ByteBuf flush() {
        return writer.writeFlush();
    }

    private GitObjectId writeSingleFileCommit() {
        GitObjectId blob = objects.write(ObjectType.BLOB, "hello\n".getBytes(StandardCharsets.UTF_8));
        GitObjectId tree = objects.write(ObjectType.TREE, tree("README.md", blob));
        String commit = """
                tree %s
                author Native Test <native@example.test> 0 +0000
                committer Native Test <native@example.test> 0 +0000

                initial
                """.formatted(tree.value());
        return objects.write(ObjectType.COMMIT, commit.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] tree(String name, GitObjectId blob) {
        byte[] prefix = ("100644 " + name + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] id = HexFormat.of().parseHex(blob.value());
        byte[] result = new byte[prefix.length + id.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(id, 0, result, prefix.length, id.length);
        return result;
    }

    private static byte[] buildPackWithBlob(byte[] blobData) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeInt(body, 0x5041434b);
        writeInt(body, 2);
        writeInt(body, 1);
        writePackObject(body, 3, blobData);
        byte[] bodyBytes = body.toByteArray();
        byte[] checksum = sha1(bodyBytes);
        ByteArrayOutputStream pack = new ByteArrayOutputStream();
        pack.write(bodyBytes);
        pack.write(checksum);
        return pack.toByteArray();
    }

    private static void writePackObject(ByteArrayOutputStream out, int typeId, byte[] data) throws Exception {
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
            if (size > 0) {
                b |= 0x80;
            }
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

    private static String blobSha1(byte[] data) {
        byte[] header = ("blob " + data.length + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] full = new byte[header.length + data.length];
        System.arraycopy(header, 0, full, 0, header.length);
        System.arraycopy(data, 0, full, header.length, data.length);
        StringBuilder hex = new StringBuilder();
        for (byte b : sha1(full)) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
