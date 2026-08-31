package pro.deta.orion.git.parser.wire;

import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
    private static final String NULL_ID = "0".repeat(40);

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
    void smartHttpPostAcceptsAdvertisedFetchServerOption() throws Exception {
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
            input.feed(fetchRequestWithCapabilities(
                    List.of("server-option=trace\n"),
                    "want " + blob.value() + "\n",
                    "done\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV2Request());

            assertThat(output.ascii()).startsWith("000dpackfile\n");
        }
    }

    @Test
    void smartHttpPostRejectsDuplicateFetchWantRef() throws Exception {
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                UnpooledByteBufAllocator.DEFAULT,
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest(
                    "want-ref refs/heads/main\n",
                    "want-ref refs/heads/main\n",
                    "done\n"));

            assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                    .serveSmartHttpPost(uploadV2Request()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Protocol v2 fetch request is invalid");
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

    @Test
    void smartHttpPostWritesLegacyUploadPackResponseOneByteAtATime()
            throws Exception {
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
            byte[] request = legacyUploadRequest(
                    "want " + blob.value() + " thin-pack ofs-delta\n",
                    "done\n");
            for (byte value : request) {
                input.feed(new byte[] {value});
            }

            session(input, output, provider).serveSmartHttpPost(uploadV1Request());

            assertThat(output.ascii()).contains("PACK");
        }
    }

    @Test
    void smartHttpPostRejectsLegacyUploadInvalidObjectId()
            throws Exception {
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                UnpooledByteBufAllocator.DEFAULT,
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRequest("want invalid\n", "done\n"));

            assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                    .serveSmartHttpPost(uploadV1Request()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(
                            "Legacy upload want must contain a 40-digit");
        }
    }

    @Test
    void smartHttpPostWritesLegacyReceivePackStatusForDelete()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider = providerWithMainRef();
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                UnpooledByteBufAllocator.DEFAULT,
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    MAIN_ID
                            + " "
                            + NULL_ID
                            + " refs/heads/main\0report-status\n"));

            session(input, output, provider).serveSmartHttpPost(receiveV1Request());

            assertThat(output.ascii())
                    .isEqualTo(
                            "000eunpack ok\n"
                                    + "0017ok refs/heads/main\n"
                                    + "0000");
            assertThat(provider.find("project")
                    .valueOrFailure("repository")
                    .refs())
                    .doesNotContainKey("refs/heads/main");
        }
    }

    @Test
    void smartHttpPostAcceptsNonAsciiLegacyReceiveRefName() throws Exception {
        String refName = "refs/heads/feature-фи";
        InMemoryNativeGitRepositoryProvider provider = providerWithMainRef();
        provider.find("project").valueOrFailure("repository")
                .updateRef(refName, NULL_ID, MAIN_ID);
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                UnpooledByteBufAllocator.DEFAULT,
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    MAIN_ID + " " + NULL_ID + " " + refName
                            + "\0report-status\n"));

            session(input, output, provider).serveSmartHttpPost(receiveV1Request());

            assertThat(new String(output.bytes(), StandardCharsets.UTF_8))
                    .contains("ok " + refName + "\n");
            assertThat(provider.find("project")
                    .valueOrFailure("repository")
                    .refs())
                    .doesNotContainKey(refName);
        }
    }

    @Test
    void smartHttpPostRejectsLegacyReceiveRefNameWithForbiddenGitCharacters()
            throws Exception {
        for (String character : List.of("~", "^", ":", "?", "*", "[", "\\")) {
            try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                    UnpooledByteBufAllocator.DEFAULT,
                    Duration.ofSeconds(1))) {
                RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
                input.feed(legacyReceiveRequest(
                        MAIN_ID
                                + " "
                                + NULL_ID
                                + " refs/heads/feature"
                                + character
                                + "x\0report-status\n"));

                assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                        .serveSmartHttpPost(receiveV1Request()))
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("invalid command");
            }
        }
    }

    @Test
    void smartHttpPostRejectsLegacyReceiveInvalidObjectId()
            throws Exception {
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                UnpooledByteBufAllocator.DEFAULT,
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    "invalid "
                            + NULL_ID
                            + " refs/heads/main\0report-status\n"));

            assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                    .serveSmartHttpPost(receiveV1Request()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(
                            "Legacy receive-pack command must contain 40-digit");
        }
    }

    @Test
    void smartHttpPostFailsWhenLegacyReceivePackBodyTimesOut()
            throws Exception {
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                UnpooledByteBufAllocator.DEFAULT,
                Duration.ofMillis(25))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    NULL_ID
                            + " "
                            + WANT
                            + " refs/heads/new\0report-status\n"));

            assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                    .serveSmartHttpPost(receiveV1Request()))
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

    private static InitialRequestData uploadV1Request() {
        return new InitialRequestData(
                InitialRequestService.UPLOAD_PACK,
                "project",
                "git.example",
                Map.of());
    }

    private static InitialRequestData receiveV1Request() {
        return new InitialRequestData(
                InitialRequestService.RECEIVE_PACK,
                "project",
                "git.example",
                Map.of());
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
        return fetchRequestWithCapabilities(List.of(), arguments);
    }

    private static byte[] fetchRequestWithCapabilities(
            List<String> capabilities,
            String... arguments) {
        ByteArrayBuilder output = new ByteArrayBuilder();
        output.writePacket("command=fetch\n");
        for (String capability : capabilities) {
            output.writePacket(capability);
        }
        output.writeAscii("0001");
        for (String argument : arguments) {
            output.writePacket(argument);
        }
        output.writeAscii("0000");
        return output.bytes();
    }

    private static byte[] legacyUploadRequest(String... lines) {
        ByteArrayBuilder output = new ByteArrayBuilder();
        output.writePacket(lines[0]);
        output.writeAscii("0000");
        for (int index = 1; index < lines.length; index++) {
            output.writePacket(lines[index]);
        }
        return output.bytes();
    }

    private static byte[] legacyReceiveRequest(String... lines) {
        ByteArrayBuilder output = new ByteArrayBuilder();
        for (String line : lines) {
            output.writePacket(line);
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
