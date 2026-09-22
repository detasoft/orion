package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.pkt.GitPktLine.MAX_PKT_LINE_LENGTH;

class GitBlockingWireTransportTest {
    private static final String MAIN_ID =
            "1111111111111111111111111111111111111111";
    private static final String TAG_ID =
            "2222222222222222222222222222222222222222";
    private static final String PEELED_TAG_ID =
            "3333333333333333333333333333333333333333";

    @Test
    void switchesBetweenPacketsAndRawBytesWithinTheSameTransportBuffer() throws Exception {
        GitBlockingWireTransport wire = input("0008wantPACK00000008done");
        assertThat(((GitPktLine.Data) wire.readPacket()).text()).isEqualTo("want");
        ByteBuf target = Unpooled.buffer(4, 4);
        try {
            assertThat(wire.readRawInto(target, 2)).isEqualTo(2);
            assertThat(wire.readRawInto(target, 8)).isEqualTo(2);
            assertThat(target.toString(StandardCharsets.US_ASCII)).isEqualTo("PACK");
            assertThat(wire.readPacket()).isSameAs(GitPktLine.Control.FLUSH);
            assertThat(((GitPktLine.Data) wire.readPacket()).text()).isEqualTo("done");
            assertThat(wire.readNextPacket()).isEmpty();
            target.clear();
            assertThat(wire.readRawInto(target, 4)).isZero();
        } finally {
            target.release();
        }
    }

    @Test
    void readsPktLineControlAndPayloadFromBufferedInput() throws Exception {
        GitBlockingWireTransport transport = input("000ahello\n0000");

        GitPktLine data = transport.readPacket();
        ByteBuf payload = transport.payloadBuffer(data);
        try {
            assertThat(data).isInstanceOf(GitPktLine.Data.class);
            assertThat(payload.toString(StandardCharsets.UTF_8))
                    .isEqualTo("hello\n");

            GitPktLine flush = transport.readPacket();
            assertThat(flush).isSameAs(GitPktLine.Control.FLUSH);
            assertThat(flush.payloadLength()).isZero();
        } finally {
            payload.release();
        }
    }

    @Test
    void rejectsMalformedPktLineHeaderFromBufferedInput() {
        GitBlockingWireTransport transport = input("zzzz");

        assertThatThrownBy(transport::readPacket)
                .isInstanceOf(GitPktLineFormatException.class)
                .hasMessageContaining("Invalid Git pkt-line header");
    }

    @Test
    void writesTextLineAndFlushToBufferedOutput() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GitBlockingWireTransport transport = output(new OutputStreamBufferedByteOutput(
                new BufferedOutputStream(bytes)));

        transport.writeTextLine("hello");
        transport.writeFlush();
        transport.flush();

        assertThat(bytes.toString(StandardCharsets.US_ASCII)).isEqualTo("000ahello\n0000");
    }

    @Test
    void sendsLegacyAdvertisement() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GitBlockingWireTransport output = output(new OutputStreamBufferedByteOutput(
                new BufferedOutputStream(bytes)));
        GitV1Advertisement advertisement = new GitV1Advertisement(
                new GitCapabilities(List.of(GitCapabilityValue.value(GitCapability.MULTI_ACK))),
                List.of(
                        new GitAdvertisedRef(
                                MAIN_ID,
                                "refs/heads/main",
                                Optional.empty()),
                        new GitAdvertisedRef(
                                TAG_ID,
                                "refs/tags/v1",
                                Optional.of(PEELED_TAG_ID))));

        output.sendAdvertisement(advertisement);

        assertThat(bytes.toString(StandardCharsets.US_ASCII))
                .isEqualTo(
                        "0047" + MAIN_ID
                                + " refs/heads/main\0multi_ack\n"
                                + "003a" + TAG_ID
                                + " refs/tags/v1\n"
                                + "003d" + PEELED_TAG_ID
                                + " refs/tags/v1^{}\n"
                                + "0000");
    }

    @Test
    void rejectsAnOversizedLaterAdvertisementLineBeforeWritingAnyBytes() {
        RecordingBufferedByteOutput sink = new RecordingBufferedByteOutput();
        GitV1Advertisement advertisement = new GitV1Advertisement(new GitCapabilities(), List.of(
                GitAdvertisedRef.direct(MAIN_ID, "refs/heads/main"),
                GitAdvertisedRef.direct(TAG_ID, "refs/heads/" + "a".repeat(MAX_PKT_LINE_LENGTH))));

        assertThatThrownBy(() -> output(sink).sendAdvertisement(advertisement))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Advertisement line exceeds");
        assertThat(sink.bytes()).isEmpty();
    }

    private static GitBlockingWireTransport input(String ascii) {
        return new GitBlockingWireTransport(
                new BufferedByteInputV2(new ByteArrayInputStream(ascii.getBytes(StandardCharsets.US_ASCII))),
                new RecordingBufferedByteOutput());
    }

    private static GitBlockingWireTransport output(BufferedByteOutput sink) {
        return new GitBlockingWireTransport(sink);
    }
}
