package pro.deta.orion.transport.git;

import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.ObjectType;
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
                input.end();

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
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest(
                    "want " + WANT + "\n",
                    "have " + have.value() + "\n",
                    "wait-for-done\n"));
            input.end();

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
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest(
                    "want " + blob.value() + "\n",
                    "thin-pack\n",
                    "done\n"));
            input.end();

            session(input, output, provider).serveSmartHttpPost(uploadV2Request());

            assertThat(output.ascii())
                    .startsWith("000dpackfile\n")
                    .contains("PACK");
        }
    }

    @Test
    void sshCommandServesLsRefsThenFetchOnSameProtocolV2Connection()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        repository.saveFiles(
                "main",
                Map.of("README.md", "payload".getBytes(StandardCharsets.US_ASCII)),
                "initial",
                GitCommitAuthor.EMPTY);
        String mainId = repository.refs().get("refs/heads/main");
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            ByteArrayBuilder request = new ByteArrayBuilder();
            request.write(lsRefsRequest());
            request.write(fetchRequest(
                    "sideband-all\n",
                    "want-ref HEAD\n",
                    "want-ref refs/heads/main\n",
                    "done\n"));
            request.writeAscii("0000");
            input.feed(request.bytes());

            session(input, output, provider).serveCommand(uploadV2Request());

            assertThat(output.ascii())
                    .contains(mainId + " HEAD symref-target:refs/heads/main")
                    .contains(mainId + " refs/heads/main")
                    .contains("\u0001wanted-refs\n")
                    .contains(mainId + " HEAD\n")
                    .contains(mainId + " refs/heads/main\n")
                    .contains("\u0001packfile\n")
                    .contains("PACK");
        }
    }

    @Test
    void smartHttpPostWritesSidebandFetchPackfileResponseForWantedRefs()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        GitObjectId blob = repository.writeObject(
                ObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef("refs/heads/main", NULL_ID, blob.value());
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest(
                    "sideband-all\n",
                    "want-ref HEAD\n",
                    "want-ref refs/heads/main\n",
                    "done\n"));
            input.end();

            session(input, output, provider).serveSmartHttpPost(uploadV2Request());

            assertThat(output.ascii())
                    .contains("\u0001wanted-refs\n")
                    .contains(blob.value() + " HEAD\n")
                    .contains(blob.value() + " refs/heads/main\n")
                    .contains("\u0001packfile\n")
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
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequestWithCapabilities(
                    List.of("server-option=trace\n"),
                    "want " + blob.value() + "\n",
                    "done\n"));
            input.end();

            session(input, output, provider).serveSmartHttpPost(uploadV2Request());

            assertThat(output.ascii()).startsWith("000dpackfile\n");
        }
    }

    @Test
    void smartHttpPostRejectsDuplicateFetchWantRef() throws Exception {
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
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
    void smartHttpPostWritesLegacyMultiAckDetailedCommonAndFinalAckOnDone()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        GitObjectId want = repository.writeObject(
                ObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        GitObjectId have = repository.writeObject(
                ObjectType.BLOB,
                "base".getBytes(StandardCharsets.US_ASCII));
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRequest(
                    "want " + want.value() + " multi_ack_detailed\n",
                    "have " + have.value() + "\n",
                    "done\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV1Request());

            assertThat(output.ascii())
                    .startsWith(
                            "0038ACK " + have.value() + " common\n"
                                    + "0031ACK " + have.value() + "\n")
                    .contains("PACK");
        }
    }

    @Test
    void smartHttpPostEndsReadyNegotiationRoundWithNak() throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        repository.saveFiles(
                "main",
                Map.of("README.md", "base".getBytes(StandardCharsets.US_ASCII)),
                "base",
                GitCommitAuthor.EMPTY);
        String have = repository.refs().get("refs/heads/main");
        repository.saveFiles(
                "main",
                Map.of("README.md", "next".getBytes(StandardCharsets.US_ASCII)),
                "next",
                GitCommitAuthor.EMPTY);
        String want = repository.refs().get("refs/heads/main");
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRound(
                    List.of("want " + want + " multi_ack_detailed\n"),
                    "have " + have + "\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV1Request());

            assertThat(output.ascii())
                    .isEqualTo(
                            "0038ACK " + have + " common\n"
                                    + "0037ACK " + have + " ready\n"
                                    + "0008NAK\n");
        }
    }

    @Test
    void smartHttpPostDoesNotSignalReadyUntilEveryWantReachesACommonHave()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        GitObjectId firstWant = repository.writeObject(
                ObjectType.BLOB,
                "first".getBytes(StandardCharsets.US_ASCII));
        GitObjectId secondWant = repository.writeObject(
                ObjectType.BLOB,
                "second".getBytes(StandardCharsets.US_ASCII));
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRound(
                    List.of(
                            "want " + firstWant.value()
                                    + " multi_ack_detailed\n",
                            "want " + secondWant.value() + "\n"),
                    "have " + firstWant.value() + "\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV1Request());

            assertThat(output.ascii())
                    .isEqualTo(
                            "0038ACK " + firstWant.value() + " common\n"
                                    + "0008NAK\n");
        }
    }

    @Test
    void commandPreservesLegacyNegotiationAcrossFlushDelimitedRounds()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        repository.saveFiles(
                "main",
                Map.of("README.md", "base".getBytes(StandardCharsets.US_ASCII)),
                "base",
                GitCommitAuthor.EMPTY);
        String have = repository.refs().get("refs/heads/main");
        repository.saveFiles(
                "main",
                Map.of("README.md", "next".getBytes(StandardCharsets.US_ASCII)),
                "next",
                GitCommitAuthor.EMPTY);
        String want = repository.refs().get("refs/heads/main");
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRounds(
                    "want " + want + " multi_ack_detailed\n",
                    List.of("have " + WANT + "\n"),
                    List.of("have " + have + "\n"),
                    "done\n"));

            session(input, output, provider).serveCommand(uploadV1Request());

            assertThat(output.ascii())
                    .contains(
                            "0008NAK\n"
                                    + "0038ACK " + have + " common\n"
                                    + "0037ACK " + have + " ready\n"
                                    + "0008NAK\n"
                                    + "0031ACK " + have + "\n")
                    .contains("PACK");
        }
    }

    @Test
    void smartHttpPostWritesLegacyMultiAckDetailedNakWhenNoHaveIsCommon()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        GitObjectId want = repository.writeObject(
                ObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRequest(
                    "want " + want.value() + " multi_ack_detailed\n",
                    "have " + WANT + "\n",
                    "done\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV1Request());

            assertThat(output.ascii())
                    .startsWith("0008NAK\n")
                    .contains("PACK");
        }
    }

    @Test
    void smartHttpPostWritesLegacyMultiAckContinueForCommonHave()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        GitObjectId want = repository.writeObject(
                ObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        GitObjectId have = repository.writeObject(
                ObjectType.BLOB,
                "base".getBytes(StandardCharsets.US_ASCII));
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRequest(
                    "want " + want.value() + " multi_ack\n",
                    "have " + have.value() + "\n",
                    "done\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV1Request());

            assertThat(output.ascii())
                    .startsWith(
                            "003aACK " + have.value() + " continue\n"
                                    + "0031ACK " + have.value() + "\n")
                    .contains("PACK");
        }
    }

    @Test
    void smartHttpPostRejectsLegacyUploadInvalidObjectId()
            throws Exception {
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
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

    private static byte[] legacyUploadRound(
            List<String> wants,
            String... haves) {
        ByteArrayBuilder output = new ByteArrayBuilder();
        for (String want : wants) {
            output.writePacket(want);
        }
        output.writeAscii("0000");
        for (String have : haves) {
            output.writePacket(have);
        }
        output.writeAscii("0000");
        return output.bytes();
    }

    private static byte[] legacyUploadRounds(
            String want,
            List<String> firstRound,
            List<String> secondRound,
            String done) {
        ByteArrayBuilder output = new ByteArrayBuilder();
        output.writePacket(want);
        output.writeAscii("0000");
        for (String have : firstRound) {
            output.writePacket(have);
        }
        output.writeAscii("0000");
        for (String have : secondRound) {
            output.writePacket(have);
        }
        output.writeAscii("0000");
        output.writePacket(done);
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
