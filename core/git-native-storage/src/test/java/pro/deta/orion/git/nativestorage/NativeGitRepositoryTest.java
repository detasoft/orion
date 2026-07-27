package pro.deta.orion.git.nativestorage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.common.GitFetchAccessRequest;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.common.GitUploadRequest;
import pro.deta.orion.git.common.GitUploadStats;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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

}
