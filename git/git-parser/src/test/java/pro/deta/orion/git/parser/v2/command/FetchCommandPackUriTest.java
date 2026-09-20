package pro.deta.orion.git.parser.v2.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.GitRepositoryContext;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackIngestor;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FetchCommandPackUriTest extends GitRepositoryContext {
    FetchCommandPackUriTest() {
        super(new GitStorageApi());
    }

    @Override
    public Optional<URI> packUri(PackId id) {
        return Optional.of(URI.create("https://git.example/project/objects/pack/" + id.toHex() + ".pack"));
    }

    @AfterEach
    void closeStorage() throws IOException {
        storage().close();
    }

    @Test
    void sendsPublishedPackChecksumAndUriAndAnEmptyInlinePack() throws Exception {
        List<ObjectId> ids = store(new byte[]{1}, new byte[]{2});
        PackId packId = storage().packIds().getFirst();
        byte[] response = fetch(ids, "https");
        assertThat(new String(response, StandardCharsets.ISO_8859_1))
                .contains("packfile-uris\n", packId.toHex() + " " + packUri(packId).orElseThrow() + "\n");
        try (IndexedPack inline = inlinePack(response)) {
            assertThat(inline.objectCount()).isZero();
        }
        try (IndexedPack published = storage().openPack(packId).orElseThrow()) {
            assertThat(published.objectIds()).containsExactlyInAnyOrderElementsOf(ids);
        }
    }

    @Test
    void leavesObjectsInlineWhenClientDoesNotAcceptUriProtocol() throws Exception {
        List<ObjectId> ids = store(new byte[]{1});
        byte[] response = fetch(ids, "http");
        assertThat(new String(response, StandardCharsets.ISO_8859_1)).doesNotContain("packfile-uris\n");
        try (IndexedPack inline = inlinePack(response)) {
            assertThat(inline.objectIds()).containsExactlyElementsOf(ids);
        }
    }

    @Test
    void doesNotExposeUnrequestedObjectsFromSharedStoredPack() throws Exception {
        List<ObjectId> ids = store(new byte[]{1}, new byte[]{2});
        byte[] response = fetch(List.of(ids.getFirst()), "https");
        assertThat(new String(response, StandardCharsets.ISO_8859_1)).doesNotContain("packfile-uris\n");
        try (IndexedPack inline = inlinePack(response)) {
            assertThat(inline.objectIds()).containsExactly(ids.getFirst());
        }
    }

    private List<ObjectId> store(byte[]... objects) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(bytes), objects.length)) {
            for (byte[] content : objects) {
                try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(content))) {
                    writer.writeObject(GitObjectType.BLOB, content.length, input);
                }
            }
            writer.finish();
        }
        try (IndexedPack pack = ingest(bytes.toByteArray())) {
            List<ObjectId> ids = new ArrayList<>(pack.objectIds());
            storage().persist(pack);
            return ids;
        }
    }

    private byte[] fetch(List<ObjectId> wants, String protocols) throws IOException {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        for (ObjectId id : wants) {
            packet(request, "want " + id.toHex());
        }
        packet(request, "packfile-uris " + protocols);
        packet(request, "done");
        request.writeBytes("0000".getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        GitCapabilities capabilities = new GitCapabilities();
        capabilities.add(GitCapabilityValue.value(GitCapability.PACKFILE_URIS));
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(request.toByteArray()))) {
            GitProtocolContext protocol = new GitProtocolContext(input, new OutputStreamBufferedByteOutput(response),
                    GitProtocolVersion.V2, GitTransport.HTTP);
            new FetchCommand(this, capabilities).action(protocol);
        }
        return response.toByteArray();
    }

    private static void packet(ByteArrayOutputStream output, String line) {
        byte[] content = (line + "\n").getBytes(StandardCharsets.UTF_8);
        output.writeBytes(String.format("%04x", content.length + 4).getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(content);
    }

    private static IndexedPack inlinePack(byte[] response) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(response))) {
            boolean pack = false;
            GitPktLine packet;
            while ((packet = GitPktLine.readNextFrom(input).orElseThrow()) != GitPktLine.Control.FLUSH) {
                if (packet instanceof GitPktLine.Data data) {
                    if (pack) {
                        assertThat(data.content()[0]).isEqualTo((byte) 1);
                        bytes.write(data.content(), 1, data.content().length - 1);
                    } else if (new String(data.content(), StandardCharsets.US_ASCII).equals("packfile\n")) {
                        pack = true;
                    }
                }
            }
        }
        return ingest(bytes.toByteArray());
    }

    private static IndexedPack ingest(byte[] bytes) throws IOException {
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes));
             PackIngestor ingestor = new PackIngestor(input, IndexedPack.create())) {
            return ingestor.ingest();
        }
    }
}
