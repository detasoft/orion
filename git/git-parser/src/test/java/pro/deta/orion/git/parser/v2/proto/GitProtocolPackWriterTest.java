package pro.deta.orion.git.parser.v2.proto;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.fetch.NegotiationResponse;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.pkt.SideBand;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.capability.GitCapabilityValue.value;

class GitProtocolPackWriterTest implements BufferedByteOutput {
    private static final ObjectId SHALLOW = new ObjectId("1".repeat(40));
    private static final ObjectId UNSHALLOW = new ObjectId("2".repeat(40));
    private static final PackId PACK = new PackId("3".repeat(40));
    private static final RefId MAIN = new RefId("refs/heads/main");
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private IOException failure;

    @Test
    void writesShallowAndUnshallowBeforeWantedRefsUrisAndPack() throws Exception {
        GitProtocolContext.Writer writer = writer(GitProtocolVersion.V2);
        GitCapabilities capabilities = new GitCapabilities();
        writer.writeShallowInfo(Set.of(SHALLOW), Set.of(UNSHALLOW), SideBand.NONE);
        writer.beginPack(capabilities, Map.of(MAIN, UNSHALLOW), Map.of(PACK, URI.create("https://e/p.pack")))
                .write("PACK-data".getBytes(StandardCharsets.US_ASCII));
        writer.endPack(capabilities);
        assertThat(bytes.toString(StandardCharsets.US_ASCII)).isEqualTo(
                "0011shallow-info\n0035shallow " + SHALLOW + "\n0037unshallow " + UNSHALLOW + "\n0001"
                        + "0010wanted-refs\n003d" + UNSHALLOW + " refs/heads/main\n0001"
                        + "0012packfile-uris\n003e" + PACK + " https://e/p.pack\n0001"
                        + "000dpackfile\n000e\u0001PACK-data0000");
    }

    @Test
    void sidebandAllPrefixesEveryDataSectionAndLeavesDelimitersUnprefixed() throws Exception {
        GitProtocolContext.Writer writer = writer(GitProtocolVersion.V2);
        GitCapabilities capabilities = new GitCapabilities(List.of(value(GitCapability.SIDEBAND_ALL)));
        writer.writeShallowInfo(Set.of(SHALLOW), Set.of(UNSHALLOW), SideBand.DATA);
        writer.beginPack(capabilities, Map.of(MAIN, UNSHALLOW), Map.of(PACK, URI.create("https://e/p.pack")))
                .write("PACK-data".getBytes(StandardCharsets.US_ASCII));
        writer.endPack(capabilities);
        assertThat(bytes.toString(StandardCharsets.US_ASCII)).isEqualTo(
                "0012\u0001shallow-info\n0036\u0001shallow " + SHALLOW
                        + "\n0038\u0001unshallow " + UNSHALLOW + "\n0001"
                        + "0011\u0001wanted-refs\n003e\u0001" + UNSHALLOW + " refs/heads/main\n0001"
                        + "0013\u0001packfile-uris\n003f\u0001" + PACK + " https://e/p.pack\n0001"
                        + "000e\u0001packfile\n000e\u0001PACK-data0000");
    }

    @Test
    void omitsEmptyV2SectionsAndFlushesAnEmptyLegacyShallowResponse() throws Exception {
        GitProtocolContext.Writer writer = writer(GitProtocolVersion.V2);
        writer.writeShallowInfo(Set.of(), Set.of(), SideBand.NONE);
        GitCapabilities capabilities = new GitCapabilities();
        writer.beginPack(capabilities, Map.of(), Map.of());
        writer.endPack(capabilities);
        assertThat(bytes.toString(StandardCharsets.US_ASCII)).isEqualTo("000dpackfile\n0000");
        bytes.reset();
        writer(GitProtocolVersion.V0).writeShallowInfo(Set.of(), Set.of(), SideBand.NONE);
        assertThat(bytes.toString(StandardCharsets.US_ASCII)).isEqualTo("0000");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void streamsLargePackAcrossBoundedPacketsWithoutLosingBytes(boolean sidebandAll) throws Exception {
        byte[] content = new byte[200_000];
        new Random(17).nextBytes(content);
        GitCapabilities capabilities = new GitCapabilities();
        if (sidebandAll) {
            capabilities.add(value(GitCapability.SIDEBAND_ALL));
        }
        GitProtocolContext.Writer writer = writer(GitProtocolVersion.V2);
        writer.beginPack(capabilities, Map.of(), Map.of()).write(content);
        writer.endPack(capabilities);
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes.toByteArray()))) {
            GitPktLine.Data header = (GitPktLine.Data) GitPktLine.readNextFrom(input).orElseThrow();
            assertThat(new String(header.content(), StandardCharsets.US_ASCII)).isEqualTo(sidebandAll ? "\u0001packfile\n" : "packfile\n");
            ByteArrayOutputStream restored = new ByteArrayOutputStream();
            int packets = 0;
            GitPktLine packet;
            while ((packet = GitPktLine.readNextFrom(input).orElseThrow()) instanceof GitPktLine.Data data) {
                assertThat(data.length()).isLessThanOrEqualTo(GitPktLine.MAX_PKT_LINE_LENGTH);
                assertThat(data.content()[0]).isEqualTo((byte) 1);
                restored.write(data.content(), 1, data.content().length - 1);
                packets++;
            }
            assertThat(packets).isGreaterThan(1);
            assertThat(packet).isSameAs(GitPktLine.Control.FLUSH);
            assertThat(GitPktLine.readNextFrom(input)).isEmpty();
            assertThat(restored.toByteArray()).isEqualTo(content);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void writesLegacyNakAndPackAndKeepsTheConnectionAvailable(boolean sideband) throws Exception {
        GitProtocolContext.Writer writer = writer(GitProtocolVersion.V0);
        GitCapabilities capabilities = new GitCapabilities();
        if (sideband) {
            capabilities.add(value(GitCapability.SIDE_BAND_64K));
        }
        writer.writeNegotiationRound(List.of(NegotiationResponse.Control.NAK), SideBand.NONE);
        writer.beginPack(capabilities, Map.of(), Map.of()).write("PACK-data".getBytes(StandardCharsets.US_ASCII));
        writer.endPack(capabilities);
        writer.writeNegotiationRound(List.of(NegotiationResponse.Control.NAK), SideBand.NONE);
        assertThat(bytes.toString(StandardCharsets.US_ASCII)).isEqualTo(sideband
                ? "0008NAK\n000e\u0001PACK-data00000008NAK\n" : "0008NAK\nPACK-data0008NAK\n");
    }

    @Test
    void propagatesDeliveryFailureWithoutFinishingTheResponse() throws Exception {
        GitProtocolContext.Writer writer = writer(GitProtocolVersion.V2);
        BufferedByteOutput pack = writer.beginPack(new GitCapabilities(), Map.of(), Map.of());
        failure = new IOException("delivery failed");
        assertThatThrownBy(() -> pack.write(new byte[]{1, 2, 3})).isSameAs(failure);
        assertThat(bytes.toString(StandardCharsets.US_ASCII)).isEqualTo("000dpackfile\n");
    }

    private GitProtocolContext.Writer writer(GitProtocolVersion version) {
        return new GitProtocolContext(new BufferedByteInputV2(InputStream.nullInputStream()),
                this, version, GitTransport.SSH).writer();
    }

    @Override
    public void write(byte[] content, int offset, int length) throws IOException {
        if (failure != null) {
            throw failure;
        }
        bytes.write(content, offset, length);
    }

    @Override
    public void write(ByteBuf buffer) throws IOException {
        byte[] content = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), content);
        write(content, 0, content.length);
    }

    @Override
    public void flush() {}
}
