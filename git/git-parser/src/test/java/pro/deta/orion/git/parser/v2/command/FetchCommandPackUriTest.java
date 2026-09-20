package pro.deta.orion.git.parser.v2.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.GitRepositoryContext;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.fetch.FetchPack;
import pro.deta.orion.git.parser.v2.fetch.FetchPlan;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.GitPackObjectResolver;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preparationReturnsFinalUrisAndInlineCount(boolean mixed) throws Exception {
        List<ObjectId> external = store(new byte[]{1}, new byte[]{2});
        PackId externalPack = storage().packIds().getFirst();
        Set<ObjectId> wanted = new LinkedHashSet<>(external);
        Set<ObjectId> inlineIds = mixed ? Set.of(store(new byte[]{3}, new byte[]{4}).getFirst()) : Set.of();
        wanted.addAll(inlineIds);
        FetchPlan plan = new FetchPlan(wanted, Map.of(), Set.of(), Set.of(), OptionalInt.empty(),
                OptionalLong.empty(), Set.of(), Optional.empty(), new GitCapabilities(), Set.of("https"));
        FetchPack pack = FetchPack.prepare(this, plan);
        Map<PackId, URI> expectedUris = Map.of(externalPack, packUri(externalPack).orElseThrow());
        assertThat(pack.packUris()).isEqualTo(expectedUris);
        assertThat(pack.objectCount()).isEqualTo(inlineIds.size());
        store(new byte[]{9});
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(output), pack.objectCount())) {
            pack.writeTo(writer);
            writer.finish();
        }
        try (IndexedPack inline = ingest(output.toByteArray())) {
            assertThat(inline.objectIds()).containsExactlyInAnyOrderElementsOf(inlineIds);
        }
        assertThat(pack.packUris()).isEqualTo(expectedUris);
        assertThat(pack.objectCount()).isEqualTo(inlineIds.size());
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
        assertThat(storage().packObjectIds(packId)).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void mixesUriPackWithOnlyRequestedEntriesFromAnotherPack() throws Exception {
        List<ObjectId> external = store(new byte[]{1}, new byte[]{2});
        PackId externalPack = storage().packIds().getFirst();
        List<ObjectId> shared = store(new byte[]{3}, new byte[]{4});
        List<ObjectId> wanted = new ArrayList<>(external);
        wanted.add(shared.getFirst());
        byte[] response = fetch(wanted, "https");
        assertThat(new String(response, StandardCharsets.ISO_8859_1))
                .contains(externalPack.toHex() + " " + packUri(externalPack).orElseThrow() + "\n");
        try (IndexedPack inline = inlinePack(response)) {
            assertThat(inline.entryCount()).isEqualTo(1);
            assertThat(inline.objectIds()).containsExactly(shared.getFirst());
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
        IndexedPack pack = ingest(bytes.toByteArray());
        List<ObjectId> ids = new ArrayList<>(pack.objectIds());
        new GitPackObjectResolver(pack, storage()).complete();
        storage().persist(pack);
        return ids;
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
