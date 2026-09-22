package pro.deta.orion.git.parser.wire;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.GitRepositoryContext;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitBlockingWireSessionTest {
    private static final RefId MAIN = new RefId("refs/heads/main");
    private static final String ZERO = "0".repeat(40);
    @TempDir
    Path directory;
    private GitStorageApi storage;
    private int opens;

    @BeforeEach
    void openStorage() throws Exception {
        storage = new GitStorageApi(directory);
    }

    @Test
    void dispatchesLsRefsThenFetchOnOneConnectionAndOpensRepositoryOnce() throws Exception {
        ObjectId id = publish();
        byte[] request = packets("command=ls-refs", "agent=test-client", "object-format=sha1", "DELIM",
                "symrefs", "ref-prefix refs/heads/", "FLUSH",
                "command=fetch", "DELIM", "want " + id, "done", "FLUSH", "FLUSH");
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        session(request, response).serveCommand(initial(GitProtocolVersion.V2, InitialRequestService.UPLOAD_PACK));
        List<GitPktLine> packets = decode(response.toByteArray());
        int advertisementEnd = packets.indexOf(GitPktLine.Control.FLUSH);
        assertThat(text(packets.getFirst())).isEqualTo("version 2\n");
        assertThat(text(packets.get(advertisementEnd + 1))).isEqualTo(id + " refs/heads/main\n");
        assertThat(packets.get(advertisementEnd + 2)).isSameAs(GitPktLine.Control.FLUSH);
        assertThat(text(packets.get(advertisementEnd + 3))).isEqualTo("packfile\n");
        assertPack(packets.subList(advertisementEnd + 4, packets.size()), id);
        assertThat(opens).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void httpFetchTerminatesTheResponseAndPreservesSidebandFraming(boolean sidebandAll) throws Exception {
        ObjectId id = publish();
        byte[] request = packets("command=fetch", "object-format=sha1", "DELIM",
                sidebandAll ? "sideband-all" : "no-progress", "want " + id, "done", "FLUSH");
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        session(request, response).serveSmartHttpPost(initial(GitProtocolVersion.V2, InitialRequestService.UPLOAD_PACK));
        List<GitPktLine> packets = decode(response.toByteArray());
        assertThat(text(packets.getFirst())).isEqualTo(sidebandAll ? "\u0001packfile\n" : "packfile\n");
        assertPack(packets.subList(1, packets.size()), id);
    }

    @Test
    void legacyHttpNegotiationCanFinishInALaterRequest() throws Exception {
        ObjectId id = publish();
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        session(packets("want " + id, "FLUSH", "have " + "f".repeat(40), "FLUSH"), response)
                .serveSmartHttpPost(initial(GitProtocolVersion.V0, InitialRequestService.UPLOAD_PACK));
        assertThat(response.toString(StandardCharsets.US_ASCII)).isEqualTo("0008NAK\n");
        response.reset();
        session(packets("want " + id, "FLUSH", "done"), response)
                .serveSmartHttpPost(initial(GitProtocolVersion.V0, InitialRequestService.UPLOAD_PACK));
        byte[] received = response.toByteArray();
        assertThat(new String(received, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("0008NAK\n");
        try (IndexedPack pack = PackTestData.ingest(Arrays.copyOfRange(received, 8, received.length),
                IndexedPack.create())) {
            assertThat(pack.find(id)).isPresent();
        }
    }

    @Test
    void advertisesLegacyVersionAndRefsFromTheNewStorage() throws Exception {
        ObjectId id = publish();
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        session(new byte[0], response).advertise(initial(GitProtocolVersion.V1, InitialRequestService.UPLOAD_PACK));
        List<GitPktLine> packets = decode(response.toByteArray());
        assertThat(text(packets.getFirst())).isEqualTo("version 1\n");
        assertThat(text(packets.get(1))).startsWith(id + " HEAD\0")
                .contains("symref=HEAD:refs/heads/main");
        assertThat(text(packets.get(2))).isEqualTo(id + " refs/heads/main\n");
        assertThat(packets.getLast()).isSameAs(GitPktLine.Control.FLUSH);
    }

    @Test
    void legacyAdvertisementsPeelAnnotatedTagsOnlyForUploadPack() throws Exception {
        ObjectId target = publish();
        ObjectId inner = PackTestData.store(storage, GitObjectType.TAG,
                ("object " + target + "\ntype blob\ntag annotated\n\nmessage\n").getBytes(StandardCharsets.US_ASCII));
        ObjectId outer = PackTestData.store(storage, GitObjectType.TAG,
                ("object " + inner + "\ntype tag\ntag nested\n\nmessage\n").getBytes(StandardCharsets.US_ASCII));
        storage.updateRefs(List.of(
                new RefUpdate(new RefId("refs/tags/annotated"), Optional.empty(), Optional.of(inner)),
                new RefUpdate(new RefId("refs/tags/lightweight"), Optional.empty(), Optional.of(target)),
                new RefUpdate(new RefId("refs/tags/nested"), Optional.empty(), Optional.of(outer))), true);
        for (GitProtocolVersion version : List.of(GitProtocolVersion.V0, GitProtocolVersion.V1)) {
            for (InitialRequestService service : InitialRequestService.values()) {
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                session(new byte[0], response).advertise(initial(version, service));
                List<GitPktLine> packets = decode(response.toByteArray());
                int first = version == GitProtocolVersion.V1 ? 1 : 0;
                assertThat(text(packets.get(first))).startsWith(target + " HEAD\0");
                List<String> refs = new ArrayList<>();
                for (GitPktLine packet : packets.subList(first + 1, packets.size() - 1)) {
                    refs.add(text(packet));
                }
                List<String> expected = new ArrayList<>(List.of(target + " refs/heads/main\n",
                        inner + " refs/tags/annotated\n"));
                if (service == InitialRequestService.UPLOAD_PACK) {
                    expected.add(target + " refs/tags/annotated^{}\n");
                }
                expected.add(target + " refs/tags/lightweight\n");
                expected.add(outer + " refs/tags/nested\n");
                if (service == InitialRequestService.UPLOAD_PACK) {
                    expected.add(target + " refs/tags/nested^{}\n");
                }
                assertThat(refs).containsExactlyElementsOf(expected);
                assertThat(packets.getLast()).isSameAs(GitPktLine.Control.FLUSH);
            }
        }
    }

    @Test
    void pushesPackThenDeletesItsRefWithoutReceivingAnotherPack() throws Exception {
        byte[] content = new byte[]{1, 2, 3};
        ObjectId id = PackTestData.objectId(GitObjectType.BLOB, content);
        byte[] request = PackTestData.join(packets(ZERO + " " + id + " " + MAIN + "\0report-status", "FLUSH"),
                PackTestData.pack(PackTestData.blob(content)));
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        session(request, response).serveSmartHttpPost(initial(GitProtocolVersion.V2, InitialRequestService.RECEIVE_PACK));
        assertThat(storage.snapshotRefs().refs()).containsEntry(MAIN, id);
        assertThat(response.toString(StandardCharsets.US_ASCII))
                .isEqualTo("000eunpack ok\n0017ok refs/heads/main\n0000");
        response.reset();
        session(packets(id + " " + ZERO + " " + MAIN + "\0report-status", "FLUSH"), response)
                .serveSmartHttpPost(initial(GitProtocolVersion.V0, InitialRequestService.RECEIVE_PACK));
        assertThat(storage.snapshotRefs().refs()).isEmpty();
        assertThat(response.toString(StandardCharsets.US_ASCII))
                .isEqualTo("000eunpack ok\n0017ok refs/heads/main\n0000");
    }

    @Test
    void cancelledPushDoesNotEmitStatusOrModifyRefs() throws Exception {
        ObjectId id = publish();
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        session(packets("FLUSH"), response)
                .serveSmartHttpPost(initial(GitProtocolVersion.V0, InitialRequestService.RECEIVE_PACK));
        assertThat(response.size()).isZero();
        assertThat(storage.snapshotRefs().refs()).containsExactlyEntriesOf(Map.of(MAIN, id));
    }

    @Test
    void rejectsMalformedOrUnsupportedCommandHeadersBeforeWritingAResponse() throws Exception {
        for (byte[] request : List.of(
                packets("command=unknown", "DELIM", "FLUSH"),
                packets("command=fetch", "FLUSH"),
                packets("command=fetch", "object-format=sha256", "DELIM", "FLUSH"),
                packets("command=fetch", "agent=one", "agent=two", "DELIM", "FLUSH"),
                packets("command=fetch", "unknown=value", "DELIM", "FLUSH"),
                packets("command=fetch"), packets("DELIM"))) {
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            assertThatThrownBy(() -> session(request, response)
                    .serveSmartHttpPost(initial(GitProtocolVersion.V2, InitialRequestService.UPLOAD_PACK)))
                    .isInstanceOf(IOException.class);
            assertThat(response.size()).isZero();
        }
    }

    private ObjectId publish() throws Exception {
        ObjectId id = PackTestData.store(storage, GitObjectType.BLOB, new byte[]{1, 2, 3});
        storage.updateRefs(List.of(new RefUpdate(MAIN, Optional.empty(), Optional.of(id))), true);
        return id;
    }

    private GitBlockingWireSession session(byte[] request, ByteArrayOutputStream response) {
        GitBlockingWireTransport wire = new GitBlockingWireTransport(
                new BufferedByteInputV2(new ByteArrayInputStream(request)), new OutputStreamBufferedByteOutput(response));
        return new GitBlockingWireSession(initial -> {
            assertThat(initial.repositoryPath()).isEqualTo("repo");
            opens++;
            return new GitRepositoryContext(storage);
        }, GitWireConfiguration.allSupported(), wire);
    }

    private static InitialRequestData initial(GitProtocolVersion version, InitialRequestService service) {
        String number = switch (version) {
            case V0 -> "0";
            case V1 -> "1";
            case V2 -> "2";
        };
        return new InitialRequestData(service, "repo", "localhost", Map.of("version", number));
    }

    private static byte[] packets(String... lines) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStreamBufferedByteOutput output = new OutputStreamBufferedByteOutput(bytes);
        for (String line : lines) {
            switch (line) {
                case "FLUSH" -> GitPktLine.Control.FLUSH.writeTo(output);
                case "DELIM" -> GitPktLine.Control.DELIMITER.writeTo(output);
                default -> new GitPktLine.Data((line + "\n").getBytes(StandardCharsets.UTF_8)).writeTo(output);
            }
        }
        return bytes.toByteArray();
    }

    private static List<GitPktLine> decode(byte[] response) throws IOException {
        List<GitPktLine> packets = new ArrayList<>();
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(response))) {
            Optional<GitPktLine> packet;
            while ((packet = GitPktLine.readNextFrom(input)).isPresent()) {
                packets.add(packet.orElseThrow());
            }
        }
        return packets;
    }

    private static String text(GitPktLine packet) throws IOException {
        return new String(((GitPktLine.Data) packet).content(), StandardCharsets.UTF_8);
    }

    private static void assertPack(List<GitPktLine> packets, ObjectId id) throws Exception {
        int end = packets.size() - 1;
        assertThat(packets.get(end)).isSameAs(GitPktLine.Control.FLUSH);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (GitPktLine packet : packets.subList(0, end)) {
            GitPktLine.Data data = (GitPktLine.Data) packet;
            assertThat(data.content()[0]).isEqualTo((byte) 1);
            bytes.write(data.content(), 1, data.content().length - 1);
        }
        try (IndexedPack pack = PackTestData.ingest(bytes.toByteArray(), IndexedPack.create())) {
            assertThat(pack.objectCount()).isEqualTo(1);
            assertThat(pack.find(id)).isPresent();
        }
    }
}
