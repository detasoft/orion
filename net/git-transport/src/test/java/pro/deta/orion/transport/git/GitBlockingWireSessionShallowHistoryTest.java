package pro.deta.orion.transport.git;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.upload.GitUploadPackException;
import pro.deta.orion.git.parser.wire.GitBlockingWireSession;
import pro.deta.orion.git.parser.wire.GitBlockingWireTransport;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.NativePackfileUriSourceFactory;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitBlockingWireSessionShallowHistoryTest {
    private static final String MAIN_ID = "1".repeat(40);
    private static final String WANT = "2".repeat(40);

    @Test
    void smartHttpPostAcceptsClientShallowStateAndRelativeDepth()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        GitObjectId blob = repository.writeObject(
                ObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        String shallow = "3".repeat(40);
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            ByteArrayBuilder request = new ByteArrayBuilder();
            request.write(command("fetch"));
            request.writePacket("want " + blob.value() + "\n");
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
    void smartHttpPostParsesUnsupportedDeepenSinceBeforeRepositoryRejection()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        GitObjectId blob = repository.writeObject(
                ObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest(
                    "want " + blob.value() + "\n",
                    "deepen-since 1700000000\n",
                    "done\n"));

            assertThatThrownBy(() -> session(input, output, provider)
                    .serveSmartHttpPost(uploadV2Request()))
                    .isInstanceOf(GitUploadPackException.class)
                    .satisfies(error -> assertThat(
                            ((GitUploadPackException) error).kind())
                            .isEqualTo(GitUploadPackException.Kind
                                    .UNSUPPORTED_FEATURE));
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
                        "done\n"),
                List.of(
                        "want " + WANT + "\n",
                        "shallow " + "3".repeat(40) + "\n",
                        "shallow " + "3".repeat(40) + "\n",
                        "done\n"))) {
            try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                    Duration.ofSeconds(1))) {
                RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
                input.feed(fetchRequest(arguments.toArray(String[]::new)));

                assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                        .serveSmartHttpPost(uploadV2Request()))
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining(
                                "Protocol v2 fetch request is invalid");
            }
        }
    }

    private static GitBlockingWireSession session(
            QueueBufferedByteInput input,
            RecordingBufferedByteOutput output,
            InMemoryNativeGitRepositoryProvider provider) {
        GitBlockingWireTransport wire =
                new GitBlockingWireTransport(input, output);
        return new GitBlockingWireSession(
                new DefaultGitNativeRepositoryService(provider),
                GitNativeRepositoryAccessHook.ALLOW_ALL,
                GitWireConfiguration.allSupported(),
                NativePackfileUriSourceFactory.NONE,
                wire);
    }

    private static InMemoryNativeGitRepositoryProvider providerWithMainRef() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        provider.create("project").valueOrFailure("repository")
                .updateRef("refs/heads/main", "0".repeat(40), MAIN_ID);
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
