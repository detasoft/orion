package pro.deta.orion.transport.git;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitFileUpdate;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.wire.GitBlockingWireSession;
import pro.deta.orion.git.parser.wire.GitBlockingWireTransport;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitBlockingWireSessionTest {
    private static final String MAIN_ID = "88d050b1908057b53d38b42702ebc659e3d7f696";
    private static final String WANT = "2".repeat(40);
    private static final String NULL_ID = "0".repeat(40);

    @Test
    void receivePreservesOriginalPackThroughTheWireAndProvider() throws Exception {
        InMemoryNativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = backend.create("project").valueOrFailure("repository");
        NativeGitFileUpdate prepared = repository.prepareFileUpdate("main", Map.of("config.txt", new byte[]{1}),
                "prepared", GitCommitAuthor.EMPTY);
        byte[] original = prepared.pack();
        NativeGitRepositoryProvider provider = new NativeGitRepositoryProvider() {
            @Override
            public boolean exists(String name) {
                return backend.exists(name);
            }

            @Override
            public Result<NativeGitRepository> find(String name) {
                return backend.find(name);
            }

            @Override
            public Result<NativeGitRepository> create(String name) {
                return backend.create(name);
            }

            @Override
            public List<RefUpdateResult> publish(NativeGitRepository selected, Optional<PackId> received,
                    List<RefUpdate> updates, boolean atomic) {
                try (IndexedPack pack = selected.storage().openPack(received.orElseThrow()).orElseThrow();
                     BufferedByteInputV2 raw = pack.input()) {
                    assertThat(raw.newInputStream().readAllBytes()).isEqualTo(original);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
                return NativeGitRepositoryProvider.super.publish(selected, received, updates, atomic);
            }
        };
        try (QueueByteSource input = new QueueByteSource(Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(NULL_ID + " " + prepared.refUpdates().getFirst().newId().orElseThrow().toHex()
                    + " refs/heads/main\0report-status\n"));
            input.feed(original);

            session(input, output, provider).serveSmartHttpPost(receiveV1Request());

            assertThat(output.ascii()).contains("ok refs/heads/main\n");
            assertThat(repository.refs()).containsEntry("refs/heads/main",
                    prepared.refUpdates().getFirst().newId().orElseThrow().toHex());
        }
    }

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
    void advertiseWritesProtocolV1MarkerForUploadPack() throws Exception {
        RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();

        session(null, output, providerWithMainRef())
                .advertise(explicitUploadV1Request());

        assertThat(output.ascii())
                .startsWith("000eversion 1\n")
                .contains(MAIN_ID + " HEAD");
    }

    @Test
    void advertiseWritesProtocolV1MarkerForReceivePack() throws Exception {
        RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();

        session(null, output, providerWithMainRef())
                .advertise(explicitReceiveV1Request());

        assertThat(output.ascii())
                .startsWith("000eversion 1\n")
                .contains(MAIN_ID + " HEAD");
    }

    @Test
    void smartHttpPostReadsLsRefsRequestOneByteAtATime() throws Exception {
        try (QueueByteSource input = new QueueByteSource(
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
        try (QueueByteSource input = new QueueByteSource(
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
        ObjectId have = repository.writeObject(
                GitObjectType.BLOB,
                "have".getBytes(StandardCharsets.US_ASCII));
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest(
                    "want " + MAIN_ID + "\n",
                    "have " + have.toHex() + "\n",
                    "wait-for-done\n"));
            input.end();

            session(input, output, provider).serveSmartHttpPost(uploadV2Request());

            assertThat(output.ascii())
                    .isEqualTo(
                            "0014acknowledgments\n"
                                    + "0031ACK " + have.toHex() + "\n"
                                    + "00000002");
        }
    }

    @Test
    void smartHttpPostWritesFetchPackfileResponse() throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        ObjectId blob = repository.writeObject(
                GitObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest(
                    "want " + blob.toHex() + "\n",
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
        try (QueueByteSource input = new QueueByteSource(
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
        ObjectId blob = repository.writeObject(
                GitObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef("refs/heads/main", NULL_ID, blob.toHex());
        try (QueueByteSource input = new QueueByteSource(
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
                    .contains(blob.toHex() + " HEAD\n")
                    .contains(blob.toHex() + " refs/heads/main\n")
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
        ObjectId blob = repository.writeObject(
                GitObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequestWithCapabilities(
                    List.of("server-option=trace\n"),
                    "want " + blob.toHex() + "\n",
                    "done\n"));
            input.end();

            session(input, output, provider).serveSmartHttpPost(uploadV2Request());

            assertThat(output.ascii()).startsWith("000dpackfile\n");
        }
    }

    @Test
    void smartHttpPostDeduplicatesFetchWantRefs() throws Exception {
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest(
                    "want-ref refs/heads/main\n",
                    "want-ref refs/heads/main\n",
                    "done\n"));

            session(input, output, providerWithMainRef()).serveSmartHttpPost(uploadV2Request());
            assertThat(output.ascii()).containsOnlyOnce(MAIN_ID + " refs/heads/main\n");

        }
    }

    @Test
    void smartHttpPostRejectsFetchWithoutWantsOrWantRefs()
            throws Exception {
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest("have " + WANT + "\n", "done\n"));

            assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                    .serveSmartHttpPost(uploadV2Request()))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void smartHttpPostFailsWhenFetchPayloadTimesOut() throws Exception {
        try (QueueByteSource input = new QueueByteSource(
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
        ObjectId blob = repository.writeObject(
                GitObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef(
                "refs/heads/main", NULL_ID, blob.toHex());
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            byte[] request = legacyUploadRequest(
                    "want " + blob.toHex() + " thin-pack ofs-delta\n",
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
        ObjectId want = repository.writeObject(
                GitObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        ObjectId have = repository.writeObject(
                GitObjectType.BLOB,
                "base".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef(
                "refs/heads/main", NULL_ID, want.toHex());
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRequest(
                    "want " + want.toHex() + " multi_ack_detailed\n",
                    "have " + have.toHex() + "\n",
                    "done\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV1Request());

            assertThat(output.ascii())
                    .startsWith(
                            "0038ACK " + have.toHex() + " common\n"
                                    + "0031ACK " + have.toHex() + "\n")
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
        try (QueueByteSource input = new QueueByteSource(
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
        repository.saveFiles("main", Map.of("first", new byte[]{1}), "first", GitCommitAuthor.EMPTY);
        repository.saveFiles("second", Map.of("second", new byte[]{2}), "second", GitCommitAuthor.EMPTY);
        ObjectId firstWant = new ObjectId(repository.refs().get("refs/heads/main"));
        ObjectId secondWant = new ObjectId(repository.refs().get("refs/heads/second"));
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRound(
                    List.of(
                            "want " + firstWant.toHex()
                                    + " multi_ack_detailed\n",
                            "want " + secondWant.toHex() + "\n"),
                    "have " + firstWant.toHex() + "\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV1Request());

            assertThat(output.ascii())
                    .isEqualTo(
                            "0038ACK " + firstWant.toHex() + " common\n"
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
        try (QueueByteSource input = new QueueByteSource(
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
        ObjectId want = repository.writeObject(
                GitObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef(
                "refs/heads/main", NULL_ID, want.toHex());
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRequest(
                    "want " + want.toHex() + " multi_ack_detailed\n",
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
        ObjectId want = repository.writeObject(
                GitObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        ObjectId have = repository.writeObject(
                GitObjectType.BLOB,
                "base".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef(
                "refs/heads/main", NULL_ID, want.toHex());
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRequest(
                    "want " + want.toHex() + " multi_ack\n",
                    "have " + have.toHex() + "\n",
                    "done\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV1Request());

            assertThat(output.ascii())
                    .startsWith(
                            "003aACK " + have.toHex() + " continue\n"
                                    + "0031ACK " + have.toHex() + "\n")
                    .contains("PACK");
        }
    }

    @Test
    void smartHttpPostRejectsLegacyUploadInvalidObjectId()
            throws Exception {
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRequest("want invalid\n", "done\n"));

            assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                    .serveSmartHttpPost(uploadV1Request()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(
                            "Invalid fetch object ID");
        }
    }

    @Test
    void smartHttpPostRejectsUnadvertisedLegacyUploadWant()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider = providerWithMainRef();
        NativeGitRepository repository =
                provider.find("project").valueOrFailure("repository");
        ObjectId hidden = repository.writeObject(
                GitObjectType.BLOB,
                "hidden".getBytes(StandardCharsets.US_ASCII));
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRequest(
                    "want " + hidden.toHex() + "\n",
                    "done\n"));

            assertThatThrownBy(() -> session(input, output, provider)
                    .serveSmartHttpPost(uploadV1Request()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(
                            "Want is not an advertised object");
        }
    }

    @Test
    void smartHttpPostWritesLegacyShallowBoundaryBeforeNegotiation()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        repository.saveFiles(
                "main",
                Map.of("README.md", "root".getBytes(StandardCharsets.US_ASCII)),
                "root",
                GitCommitAuthor.EMPTY);
        repository.saveFiles(
                "main",
                Map.of("README.md", "tip".getBytes(StandardCharsets.US_ASCII)),
                "tip",
                GitCommitAuthor.EMPTY);
        String tip = repository.refs().get("refs/heads/main");
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRound(
                    List.of(
                            "want " + tip + " shallow\n",
                            "deepen 1\n"),
                    "done\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV1Request());

            assertThat(output.ascii())
                    .startsWith("0035shallow " + tip + "\n0000")
                    .contains("NAK\n")
                    .contains("PACK");
        }
    }

    @Test
    void smartHttpPostRejectsContradictoryLegacyDeepeningForms()
            throws Exception {
        for (List<String> requestLines : List.of(
                List.of(
                        "want " + MAIN_ID + " shallow\n",
                        "deepen 1\n",
                        "deepen-since 1\n"),
                List.of(
                        "want " + MAIN_ID + " shallow\n",
                        "deepen-relative\n"))) {
            try (QueueByteSource input = new QueueByteSource(
                    Duration.ofSeconds(1))) {
                RecordingBufferedByteOutput output =
                        new RecordingBufferedByteOutput();
                input.feed(legacyUploadRound(requestLines, "done\n"));

                assertThatThrownBy(() -> session(
                        input,
                        output,
                        providerWithMainRef())
                        .serveSmartHttpPost(uploadV1Request()))
                        .isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void smartHttpPostWritesLegacyReceivePackStatusForDelete()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider = providerWithMainRef();
        try (QueueByteSource input = new QueueByteSource(
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
    void smartHttpPostAcceptsReceiveShallowPrefixesBeforeCommands()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider = providerWithMainRef();
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    "shallow " + "a".repeat(40) + "\n",
                    MAIN_ID
                            + " "
                            + NULL_ID
                            + " refs/heads/main\0report-status\n"));

            session(input, output, provider).serveSmartHttpPost(receiveV1Request());

            assertThat(output.ascii())
                    .contains("unpack ok\n")
                    .contains("ok refs/heads/main\n");
            assertThat(provider.find("project")
                    .valueOrFailure("repository")
                    .refs())
                    .doesNotContainKey("refs/heads/main");
        }
    }

    @Test
    void smartHttpPostRejectsReceiveShallowPrefixAfterCommand()
            throws Exception {
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    MAIN_ID
                            + " "
                            + NULL_ID
                            + " refs/heads/main\0report-status\n",
                    "shallow " + "a".repeat(40) + "\n"));

            assertThatThrownBy(() -> session(
                    input,
                    output,
                    providerWithMainRef()).serveSmartHttpPost(receiveV1Request()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(
                            "Shallow declarations must precede push commands");
        }
    }

    @Test
    void receivePackAppliesValidCommandsAfterNonAtomicStaleCommand()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider = providerWithMainRef();
        provider.find("project").valueOrFailure("repository")
                .updateRef("refs/heads/feature", NULL_ID, MAIN_ID);
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    "3".repeat(40)
                            + " "
                            + NULL_ID
                            + " refs/heads/main\0report-status\n",
                    MAIN_ID
                            + " "
                            + NULL_ID
                            + " refs/heads/feature\n"));

            session(input, output, provider).serveSmartHttpPost(receiveV1Request());

            assertThat(output.ascii())
                    .contains("ng refs/heads/main stale info\n")
                    .contains("ok refs/heads/feature\n");
            assertThat(provider.find("project")
                    .valueOrFailure("repository")
                    .refs())
                    .containsEntry("refs/heads/main", MAIN_ID)
                    .doesNotContainKey("refs/heads/feature");
        }
    }

    @Test
    void receivePackRollsBackValidCommandsWhenAtomicCommandIsStale()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider = providerWithMainRef();
        provider.find("project").valueOrFailure("repository")
                .updateRef("refs/heads/feature", NULL_ID, MAIN_ID);
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    "3".repeat(40)
                            + " "
                            + NULL_ID
                            + " refs/heads/main\0report-status atomic\n",
                    MAIN_ID
                            + " "
                            + NULL_ID
                            + " refs/heads/feature\n"));

            session(input, output, provider).serveSmartHttpPost(receiveV1Request());

            assertThat(output.ascii())
                    .contains("ng refs/heads/main stale info\n")
                    .contains(
                            "ng refs/heads/feature atomic push failure\n");
            assertThat(provider.find("project")
                    .valueOrFailure("repository")
                    .refs())
                    .containsEntry("refs/heads/main", MAIN_ID)
                    .containsEntry("refs/heads/feature", MAIN_ID);
        }
    }

    @Test
    void smartHttpReceivePackV2OfferFallsBackToLegacyProtocol()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider = providerWithMainRef();
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    MAIN_ID
                            + " "
                            + NULL_ID
                            + " refs/heads/main\0report-status\n"));

            session(input, output, provider).serveSmartHttpPost(receiveV2Request());

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
    void smartHttpPostReportsMalformedReceivePackThroughStatusV2()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        provider.create("project").valueOrFailure("repository");
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            ByteArrayBuilder request = new ByteArrayBuilder();
            request.writePacket(
                    NULL_ID
                            + " "
                            + WANT
                            + " refs/heads/main\0report-status-v2\n");
            request.writeAscii("0000BAD");
            input.feed(request.bytes());
            input.end();

            session(input, output, provider).serveSmartHttpPost(receiveV1Request());

            assertThat(output.ascii())
                    .contains("unpack unpacker error\n")
                    .contains("ng refs/heads/main unpacker error\n");
            assertThat(provider.find("project")
                    .valueOrFailure("repository")
                    .refs())
                    .doesNotContainKey("refs/heads/main");
        }
    }

    @Test
    void smartHttpPostAcceptsEmptyReceiveCommandSection() throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        provider.create("project").valueOrFailure("repository");
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed("0000");

            session(input, output, provider).serveSmartHttpPost(receiveV1Request());

            assertThat(output.ascii()).isEmpty();
        }
    }

    @Test
    void smartHttpPostAcceptsNonAsciiLegacyReceiveRefName() throws Exception {
        String refName = "refs/heads/feature-фи";
        InMemoryNativeGitRepositoryProvider provider = providerWithMainRef();
        provider.find("project").valueOrFailure("repository")
                .updateRef(refName, NULL_ID, MAIN_ID);
        try (QueueByteSource input = new QueueByteSource(
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
            try (QueueByteSource input = new QueueByteSource(
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
                        .hasMessageContaining("Invalid push command");
            }
        }
    }

    @Test
    void smartHttpPostRejectsLegacyReceiveInvalidObjectId()
            throws Exception {
        try (QueueByteSource input = new QueueByteSource(
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
                            "Invalid push command");
        }
    }

    @Test
    void smartHttpPostReportsFailureWhenReceivePackBodyTimesOut()
            throws Exception {
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofMillis(25))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    NULL_ID
                            + " "
                            + WANT
                            + " refs/heads/new\0report-status\n"));

            InMemoryNativeGitRepositoryProvider provider = providerWithMainRef();
            session(input, output, provider).serveSmartHttpPost(receiveV1Request());
            assertThat(output.ascii()).contains("unpack unpacker error\n", "ng refs/heads/new unpacker error\n");
            assertThat(provider.find("project").valueOrFailure("repository").refs())
                    .doesNotContainKey("refs/heads/new");

        }
    }

    private static GitBlockingWireSession session(
            QueueByteSource input,
            RecordingBufferedByteOutput output,
            NativeGitRepositoryProvider provider) {
        GitBlockingWireTransport wire = input == null
                ? new GitBlockingWireTransport(output)
                : new GitBlockingWireTransport(new BufferedByteInputV2(input), output);
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

    private static InitialRequestData uploadV1Request() {
        return new InitialRequestData(
                InitialRequestService.UPLOAD_PACK,
                "project",
                "git.example",
                Map.of());
    }

    private static InitialRequestData explicitUploadV1Request() {
        return new InitialRequestData(
                InitialRequestService.UPLOAD_PACK,
                "project",
                "git.example",
                Map.of("version", "1"));
    }

    private static InitialRequestData receiveV1Request() {
        return new InitialRequestData(
                InitialRequestService.RECEIVE_PACK,
                "project",
                "git.example",
                Map.of());
    }

    private static InitialRequestData explicitReceiveV1Request() {
        return new InitialRequestData(
                InitialRequestService.RECEIVE_PACK,
                "project",
                "git.example",
                Map.of("version", "1"));
    }

    private static InitialRequestData receiveV2Request() {
        return new InitialRequestData(
                InitialRequestService.RECEIVE_PACK,
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
