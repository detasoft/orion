package pro.deta.orion.git.parser.wire;

import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitBlockingWireSessionTest {
    private static final String MAIN_ID = "1".repeat(40);
    private static final String WANT = "2".repeat(40);

    @Test
    void advertiseWritesProtocolV2Capabilities() throws Exception {
        RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
        session(null, output, providerWithMainRef()).advertise(uploadV2Request());

        assertThat(output.ascii())
                .startsWith("000eversion 2\n")
                .contains("fetch=")
                .contains("packfile-uris");
    }

    @Test
    void smartHttpPostReadsLsRefsRequestOneByteAtATime() throws Exception {
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                UnpooledByteBufAllocator.DEFAULT,
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Void> result = executor.submit(() -> {
                    session(input, output, providerWithMainRef())
                            .serveSmartHttpPost(uploadV2Request());
                    return null;
                });

                for (byte value : lsRefsRequest()) {
                    input.feed(new byte[] {value});
                }

                result.get(2, TimeUnit.SECONDS);
                assertThat(output.ascii())
                        .doesNotContain("version 2\n")
                        .contains(MAIN_ID + " HEAD symref-target:refs/heads/main")
                        .contains(MAIN_ID + " refs/heads/main");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void smartHttpPostFailsWhenLsRefsPayloadTimesOut() throws Exception {
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                UnpooledByteBufAllocator.DEFAULT,
                Duration.ofMillis(25))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed("0012command=ls");

            assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                    .serveSmartHttpPost(uploadV2Request()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Timed out");
        }
    }

    @Test
    void smartHttpPostWritesFetchNegotiationAcknowledgments()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                providerWithMainRef();
        NativeGitRepository repository =
                provider.find("project").valueOrFailure("repository");
        GitObjectId have = repository.writeObject(
                ObjectType.BLOB,
                "have".getBytes(StandardCharsets.US_ASCII));
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                UnpooledByteBufAllocator.DEFAULT,
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest(
                    "want " + WANT + "\n",
                    "have " + have.value() + "\n",
                    "wait-for-done\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV2Request());

            assertThat(output.ascii())
                    .isEqualTo(
                            "0014acknowledgments\n"
                                    + "0031ACK " + have.value() + "\n"
                                    + "0000");
        }
    }

    @Test
    void smartHttpPostWritesFetchPackfileResponse() throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        GitObjectId blob = repository.writeObject(
                ObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                UnpooledByteBufAllocator.DEFAULT,
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest(
                    "want " + blob.value() + "\n",
                    "thin-pack\n",
                    "done\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV2Request());

            assertThat(output.ascii())
                    .startsWith("000dpackfile\n")
                    .contains("PACK");
        }
    }

    @Test
    void smartHttpPostRejectsFetchWithoutWantsOrWantRefs()
            throws Exception {
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                UnpooledByteBufAllocator.DEFAULT,
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest("have " + WANT + "\n", "done\n"));

            assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                    .serveSmartHttpPost(uploadV2Request()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Protocol v2 fetch request is invalid");
        }
    }

    @Test
    void smartHttpPostFailsWhenFetchPayloadTimesOut() throws Exception {
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                UnpooledByteBufAllocator.DEFAULT,
                Duration.ofMillis(25))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(command("fetch"));
            input.feed("0012want " + WANT.substring(0, 8));

            assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                    .serveSmartHttpPost(uploadV2Request()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Timed out");
        }
    }

    private static GitBlockingWireSession session(
            QueueBufferedByteInput input,
            RecordingBufferedByteOutput output,
            InMemoryNativeGitRepositoryProvider provider) {
        return new GitBlockingWireSession(
                UnpooledByteBufAllocator.DEFAULT,
                provider,
                GitNativeRepositoryAccessHook.ALLOW_ALL,
                GitWireConfiguration.allSupported(),
                NativePackfileUriSourceFactory.NONE,
                input,
                output);
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

    private static byte[] lsRefsRequest() {
        ByteArrayBuilder output = new ByteArrayBuilder();
        output.write(command("ls-refs"));
        output.writePacket("symrefs\n");
        output.writePacket("ref-prefix HEAD\n");
        output.writePacket("ref-prefix refs/heads/\n");
        output.writeAscii("0000");
        return output.bytes();
    }

    private static byte[] fetchRequest(String... arguments) {
        ByteArrayBuilder output = new ByteArrayBuilder();
        output.write(command("fetch"));
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
            byte[] payloadBytes = payload.getBytes(StandardCharsets.US_ASCII);
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
