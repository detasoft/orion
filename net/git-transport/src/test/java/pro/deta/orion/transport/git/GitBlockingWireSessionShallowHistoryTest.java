package pro.deta.orion.transport.git;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.GitFile;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.wire.GitBlockingWireSession;
import pro.deta.orion.git.parser.wire.GitBlockingWireTransport;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitBlockingWireSessionShallowHistoryTest {
    private static final String MAIN_ID = "88d050b1908057b53d38b42702ebc659e3d7f696";
    private static final String WANT = "2".repeat(40);

    @Test
    void smartHttpPostAcceptsClientShallowStateAndRelativeDepth()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        ObjectId blob = repository.writeObject(
                GitObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        String shallow = "3".repeat(40);
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            ByteArrayBuilder request = new ByteArrayBuilder();
            request.write(command("fetch"));
            request.writePacket("want " + blob.toHex() + "\n");
            request.writePacket("shallow " + shallow + "\n");
            request.writePacket("deepen 1\n");
            request.writePacket("deepen-relative\n");
            request.writePacket("done\n");
            request.writeAscii("0000");
            for (byte value : request.bytes()) {
                input.feed(new byte[] {value});
            }
            input.end();

            session(input, output, provider).serveSmartHttpPost(uploadV2Request());

            assertThat(output.ascii())
                    .startsWith("000dpackfile\n")
                    .contains("PACK");
        }
    }

    @Test
    void smartHttpPostSerializesShallowInfoForDeepenSince()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        ObjectId rootBlob = repository.writeObject(
                GitObjectType.BLOB,
                "root".getBytes(StandardCharsets.US_ASCII));
        ObjectId rootTree = repository.writeObject(
                GitObjectType.TREE,
                treeEntry("100644", "root.txt", rootBlob));
        ObjectId rootCommit = writeCommit(
                repository,
                rootTree,
                null,
                "root",
                100);
        ObjectId tipBlob = repository.writeObject(
                GitObjectType.BLOB,
                "tip".getBytes(StandardCharsets.US_ASCII));
        ObjectId tipTree = repository.writeObject(
                GitObjectType.TREE,
                treeEntry("100644", "tip.txt", tipBlob));
        ObjectId tipCommit = writeCommit(
                repository,
                tipTree,
                rootCommit,
                "tip",
                300);
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest(
                    "want " + tipCommit.toHex() + "\n",
                    "deepen-since 200\n",
                    "done\n"));
            input.end();

            session(input, output, provider).serveSmartHttpPost(uploadV2Request());

            assertThat(output.ascii())
                    .startsWith("0011shallow-info\n")
                    .contains("shallow " + tipCommit.toHex() + "\n")
                    .contains("packfile\n");
        }
    }

    @Test
    void smartHttpPostRejectsContradictoryDeepeningForms()
            throws Exception {
        for (List<String> arguments : List.of(
                List.of(
                        "want " + WANT + "\n",
                        "deepen 1\n",
                        "deepen-since 1700000000\n",
                        "done\n"),
                List.of(
                        "want " + WANT + "\n",
                        "deepen-relative\n",
                        "done\n"))) {
            try (QueueByteSource input = new QueueByteSource(
                    Duration.ofSeconds(1))) {
                RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
                input.feed(fetchRequest(arguments.toArray(String[]::new)));

                assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                        .serveSmartHttpPost(uploadV2Request()))
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining(
                                arguments.contains("deepen-relative\n")
                                        ? "deepen-relative requires depth"
                                        : "Depth cannot be combined");
            }
        }
    }

    @Test
    void duplicateShallowDeclarationsDoNotDuplicateTheResponse() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("project").valueOrFailure("repository");
        repository.saveFiles("main", Map.of("file", GitFile.regular(new byte[]{1})), Set.of(), "initial",
                pro.deta.orion.git.nativestorage.GitCommitAuthor.EMPTY);
        String tip = repository.refs().get("refs/heads/main");
        try (QueueByteSource input = new QueueByteSource(Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest("want " + tip + "\n", "shallow " + tip + "\n",
                    "shallow " + tip + "\n", "done\n"));
            input.end();
            session(input, output, provider).serveSmartHttpPost(uploadV2Request());
            assertThat(output.ascii()).containsOnlyOnce("packfile\n").contains("PACK");
        }
    }

    private static GitBlockingWireSession session(
            QueueByteSource input,
            RecordingBufferedByteOutput output,
            InMemoryNativeGitRepositoryProvider provider) {
        GitBlockingWireTransport wire =
                new GitBlockingWireTransport(new BufferedByteInputV2(input), output);
        return new GitBlockingWireSession(
                data -> new DefaultGitNativeRepositoryService(provider).open(
                        data, GitNativeRepositoryAccessHook.ALLOW_ALL),
                GitWireConfiguration.allSupported(),
                wire);
    }

    private static InMemoryNativeGitRepositoryProvider providerWithMainRef() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("project").valueOrFailure("repository");
        ObjectId id = repository.writeObject(GitObjectType.BLOB, "main".getBytes(StandardCharsets.US_ASCII));
        assertThat(id.toHex()).isEqualTo(MAIN_ID);
        repository.updateRef("refs/heads/main", "0".repeat(40), id.toHex());
        return provider;
    }

    private static InitialRequestData uploadV2Request() {
        return new InitialRequestData(
                InitialRequestService.UPLOAD_PACK,
                "project",
                "git.example",
                Map.of("version", "2"));
    }

    private static byte[] fetchRequest(String... arguments) {
        ByteArrayBuilder output = new ByteArrayBuilder();
        output.writePacket("command=fetch\n");
        output.writeAscii("0001");
        for (String argument : arguments) {
            output.writePacket(argument);
        }
        output.writeAscii("0000");
        return output.bytes();
    }

    private static byte[] command(String command) {
        ByteArrayBuilder output = new ByteArrayBuilder();
        output.writePacket("command=" + command + "\n");
        output.writeAscii("0001");
        return output.bytes();
    }

    private static ObjectId writeCommit(
            NativeGitRepository repository,
            ObjectId tree,
            ObjectId parent,
            String message,
            long committerTimestamp) {
        StringBuilder data = new StringBuilder("tree ")
                .append(tree)
                .append('\n');
        if (parent != null) {
            data.append("parent ").append(parent).append('\n');
        }
        data.append("author Test <test@example.com> 0 +0000\n")
                .append("committer Test <test@example.com> ")
                .append(committerTimestamp)
                .append(" +0000\n")
                .append('\n')
                .append(message)
                .append('\n');
        return repository.writeObject(
                GitObjectType.COMMIT,
                data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] treeEntry(
            String mode,
            String name,
            ObjectId objectId) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes((mode + " " + name + "\0")
                .getBytes(StandardCharsets.UTF_8));
        output.writeBytes(HexFormat.of().parseHex(objectId.toHex()));
        return output.toByteArray();
    }

    private static final class ByteArrayBuilder {
        private byte[] bytes = new byte[128];
        private int size;

        void writePacket(String payload) {
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            writeAscii("%04x".formatted(payloadBytes.length + 4));
            write(payloadBytes);
        }

        void writeAscii(String value) {
            write(value.getBytes(StandardCharsets.US_ASCII));
        }

        byte[] bytes() {
            byte[] copy = new byte[size];
            System.arraycopy(bytes, 0, copy, 0, size);
            return copy;
        }

        private void write(byte[] source) {
            if (size + source.length > bytes.length) {
                byte[] next = new byte[Math.max(
                        bytes.length * 2,
                        size + source.length)];
                System.arraycopy(bytes, 0, next, 0, size);
                bytes = next;
            }
            System.arraycopy(source, 0, bytes, size, source.length);
            size += source.length;
        }
    }
}
