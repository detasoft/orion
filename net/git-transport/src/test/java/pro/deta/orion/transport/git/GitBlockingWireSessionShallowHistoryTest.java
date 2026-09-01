package pro.deta.orion.transport.git;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.upload.NativeFetchResponse;
import pro.deta.orion.git.parser.wire.GitBlockingWireSession;
import pro.deta.orion.git.parser.wire.GitBlockingWireTransport;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryService;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryService.ReceivePackStatus;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.NativePackfileUriSourceFactory;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.exchange.LegacyReceivePack;
import pro.deta.orion.git.parser.wire.exchange.LsRefsRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.ArrayList;
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
    void smartHttpPostSerializesShallowInfoForDeepenSince()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        GitObjectId rootBlob = repository.writeObject(
                ObjectType.BLOB,
                "root".getBytes(StandardCharsets.US_ASCII));
        GitObjectId rootTree = repository.writeObject(
                ObjectType.TREE,
                treeEntry("100644", "root.txt", rootBlob));
        GitObjectId rootCommit = writeCommit(
                repository,
                rootTree,
                null,
                "root",
                100);
        GitObjectId tipBlob = repository.writeObject(
                ObjectType.BLOB,
                "tip".getBytes(StandardCharsets.US_ASCII));
        GitObjectId tipTree = repository.writeObject(
                ObjectType.TREE,
                treeEntry("100644", "tip.txt", tipBlob));
        GitObjectId tipCommit = writeCommit(
                repository,
                tipTree,
                rootCommit,
                "tip",
                300);
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest(
                    "want " + tipCommit.value() + "\n",
                    "deepen-since 200\n",
                    "done\n"));
            input.end();

            session(input, output, provider).serveSmartHttpPost(uploadV2Request());

            assertThat(output.ascii())
                    .startsWith("0011shallow-info\n")
                    .contains("shallow " + tipCommit.value() + "\n")
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
                new RecordingGitNativeRepositoryService(provider),
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

    private static GitObjectId writeCommit(
            NativeGitRepository repository,
            GitObjectId tree,
            GitObjectId parent,
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
                ObjectType.COMMIT,
                data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] treeEntry(
            String mode,
            String name,
            GitObjectId objectId) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes((mode + " " + name + "\0")
                .getBytes(StandardCharsets.UTF_8));
        output.writeBytes(HexFormat.of().parseHex(objectId.value()));
        return output.toByteArray();
    }

    private static final class RecordingGitNativeRepositoryService
            implements GitNativeRepositoryService {
        private final InMemoryNativeGitRepositoryProvider provider;

        private RecordingGitNativeRepositoryService(
                InMemoryNativeGitRepositoryProvider provider) {
            this.provider = provider;
        }

        @Override
        public GitV1Advertisement legacyUploadPackAdvertisement(
                InitialRequestData data,
                GitNativeRepositoryAccessHook accessHook,
                GitWireConfiguration configuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GitV1Advertisement legacyReceivePackAdvertisement(
                InitialRequestData data,
                GitNativeRepositoryAccessHook accessHook,
                GitWireConfiguration configuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public NativePackProducer legacyUploadPack(
                InitialRequestData data,
                NativeFetchRequest request,
                GitNativeRepositoryAccessHook accessHook) {
            throw new UnsupportedOperationException();
        }

        @Override
        public NativeFetchResponse protocolV2Fetch(
                InitialRequestData data,
                NativeFetchRequest request,
                GitNativeRepositoryAccessHook accessHook,
                NativePackfileUriSourceFactory packfileUriSourceFactory) {
            return repository(data).fetchResponse(request);
        }

        @Override
        public List<GitObjectId> protocolV2FetchAcknowledgments(
                InitialRequestData data,
                NativeFetchRequest request,
                GitNativeRepositoryAccessHook accessHook) {
            return new ArrayList<>(request.haves());
        }

        @Override
        public List<GitObjectId> commonHaves(
                InitialRequestData data,
                Iterable<GitObjectId> haves,
                GitNativeRepositoryAccessHook accessHook) {
            return List.of();
        }

        @Override
        public boolean legacyUploadReady(
                InitialRequestData data,
                Iterable<GitObjectId> wants,
                Iterable<GitObjectId> commonHaves,
                GitNativeRepositoryAccessHook accessHook) {
            return false;
        }

        @Override
        public PackIngestionSession beginLegacyReceivePack(
                InitialRequestData data,
                GitNativeRepositoryAccessHook accessHook) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ReceivePackStatus> completeLegacyReceivePack(
                LegacyReceivePack receivePack,
                GitNativeRepositoryAccessHook accessHook) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GitLsRefsResponse lsRefs(
                InitialRequestData data,
                LsRefsRequest request,
                GitNativeRepositoryAccessHook accessHook) {
            throw new UnsupportedOperationException();
        }

        private NativeGitRepository repository(InitialRequestData data) {
            return provider.find(data.getRepositoryPath())
                    .valueOrFailure("repository");
        }
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
