package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.pkt.GitPktLine.MAX_PKT_LINE_LENGTH;

class GitBlockingWireTransportTest {
    private static final String MAIN_ID =
            "1111111111111111111111111111111111111111";
    private static final String TAG_ID =
            "2222222222222222222222222222222222222222";
    private static final String PEELED_TAG_ID =
            "3333333333333333333333333333333333333333";
    private final ByteBufAllocator allocator = UnpooledByteBufAllocator.DEFAULT;

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
    void writesPktLinePacketsToBufferedOutput() throws Exception {
        RecordingOutput sink = new RecordingOutput();
        GitBlockingWireTransport transport = output(sink);
        ByteBuf payload = Unpooled.copiedBuffer(
                "hello",
                StandardCharsets.UTF_8);
        try {
            transport.writeData(payload);
            assertThat(payload.readerIndex()).isZero();
            assertThat(payload.refCnt()).isEqualTo(1);
            transport.writeFlush();
            transport.flush();

            assertThat(sink.writeLengths()).containsExactly(4, 5, 4);
            assertThat(sink.byteArrayWriteLengths()).containsExactly(4, 5, 4);
            assertThat(sink.byteBufWriteLengths()).isEmpty();
            assertThat(sink.ascii()).isEqualTo("0009hello0000");
        } finally {
            payload.release();
        }
    }

    @Test
    void writesSidebandPacketsAndSplitsAtPktLineLimit() throws Exception {
        RecordingOutput sink = new RecordingOutput();
        GitBlockingWireTransport transport = output(sink);
        int firstPayloadLength = MAX_PKT_LINE_LENGTH - 5;
        ByteBuf payload = allocator.buffer(
                firstPayloadLength + 3,
                firstPayloadLength + 3);
        try {
            payload.writeBytes(repeated((byte) 'a', firstPayloadLength));
            payload.writeBytes(new byte[] {'b', 'c', 'd'});

            transport.writeSideBandData(payload);

            byte[] bytes = sink.bytes();
            assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII))
                    .isEqualTo("fff0");
            assertThat(bytes[4]).isEqualTo((byte) 1);
            assertThat(bytes[4 + firstPayloadLength]).isEqualTo((byte) 'a');
            int secondHeaderOffset = MAX_PKT_LINE_LENGTH;
            assertThat(new String(
                    bytes,
                    secondHeaderOffset,
                    4,
                    StandardCharsets.US_ASCII))
                    .isEqualTo("0008");
            assertThat(bytes[secondHeaderOffset + 4]).isEqualTo((byte) 1);
            assertThat(Arrays.copyOfRange(
                    bytes,
                    secondHeaderOffset + 5,
                    secondHeaderOffset + 8))
                    .containsExactly((byte) 'b', (byte) 'c', (byte) 'd');
        } finally {
            payload.release();
        }
    }

    @Test
    void writesProgressAndErrorSidebandPackets() throws Exception {
        RecordingOutput sink = new RecordingOutput();
        GitBlockingWireTransport transport = output(sink);

        transport.writeSideBandProgress("counting");
        transport.writeSideBandError("failed");

        assertThat(sink.ascii()).isEqualTo("000d\u0002counting000b\u0003failed");
    }

    @Test
    void writesSidebandHeaderSeparatelyFromPayload() throws Exception {
        RecordingOutput sink = new RecordingOutput();
        GitBlockingWireTransport transport = output(sink);
        ByteBuf payload = Unpooled.copiedBuffer(
                "hello",
                StandardCharsets.UTF_8);
        try {
            transport.writeSideBandData(payload);

            assertThat(sink.writeLengths()).containsExactly(5, 5);
            assertThat(sink.ascii()).isEqualTo("000a\u0001hello");
        } finally {
            payload.release();
        }
    }

    @Test
    void writesStringSidebandWithoutAllocatorBuffer() throws Exception {
        RecordingOutput sink = new RecordingOutput();
        GitBlockingWireTransport transport = output(sink);

        transport.writeSideBandProgress("counting");

        assertThat(sink.byteArrayWriteLengths()).containsExactly(5, 8);
        assertThat(sink.byteBufWriteLengths()).isEmpty();
        assertThat(sink.ascii()).isEqualTo("000d\u0002counting");
    }

    @Test
    void sendsProtocolV2UploadPackAdvertisement() throws Exception {
        RecordingBufferedByteOutput sink = new RecordingBufferedByteOutput();
        GitBlockingWireTransport output = output(sink);

        output.sendV2UploadPackAdvertisement(
                GitWireConfiguration.allSupported().protocolV2());

        assertThat(sink.ascii())
                .isEqualTo(
                        "000eversion 2\n"
                                + "0013ls-refs=unborn\n"
                                + "004efetch=shallow wait-for-done filter "
                                + "ref-in-want sideband-all packfile-uris\n"
                                + "0012server-option\n"
                                + "0000");
    }

    @Test
    void writesLargeResponseSynchronouslyToBufferedByteOutput()
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GitBlockingWireTransport output = output(
                new OutputStreamBufferedByteOutput(bytes));
        List<GitLsRefsResponse.Ref> refs = new ArrayList<>();
        for (int index = 0; index < 2_000; index++) {
            refs.add(new GitLsRefsResponse.DirectRef(
                    "%040x".formatted(index),
                    "refs/heads/branch-" + index,
                    Optional.empty(),
                    Optional.empty()));
        }

        output.sendLsRefs(new GitLsRefsResponse(refs));

        byte[] wire = bytes.toByteArray();
        assertThat(wire.length)
                .isGreaterThan(GitBlockingWireTransport.BUFFER_CAPACITY);
        assertThat(new String(
                wire,
                wire.length - 4,
                4,
                StandardCharsets.US_ASCII))
                .isEqualTo("0000");
    }

    @Test
    void sendsLegacyAdvertisement() throws Exception {
        RecordingBufferedByteOutput sink = new RecordingBufferedByteOutput();
        GitBlockingWireTransport output = output(sink);
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

        assertThat(sink.ascii())
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
    void rejectsInvalidLsRefsObjectId() {
        GitBlockingWireTransport output = output(new RecordingBufferedByteOutput());

        assertThatThrownBy(() -> output.sendLsRefs(
                new GitLsRefsResponse(List.of(
                        new GitLsRefsResponse.DirectRef(
                                "not-an-object-id",
                                "refs/heads/main",
                                Optional.empty(),
                                Optional.empty())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Git object ID must contain 40 hexadecimal digits");
    }

    @Test
    void rejectsBlankGitErrorMessage() {
        GitBlockingWireTransport output = output(new RecordingBufferedByteOutput());

        assertThatThrownBy(() -> output.sendError(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message must not be blank");
    }

    private static GitBlockingWireTransport input(String ascii) {
        return new GitBlockingWireTransport(
                new BufferedByteInputV2(new ByteArrayInputStream(ascii.getBytes(StandardCharsets.US_ASCII))),
                new RecordingBufferedByteOutput());
    }

    private static GitBlockingWireTransport output(BufferedByteOutput sink) {
        return new GitBlockingWireTransport(sink);
    }

    private static byte[] repeated(byte value, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static final class RecordingOutput implements BufferedByteOutput {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final List<Integer> writeLengths = new ArrayList<>();
        private final List<Integer> byteArrayWriteLengths = new ArrayList<>();
        private final List<Integer> byteBufWriteLengths = new ArrayList<>();

        @Override
        public void write(ByteBuf buffer) {
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            writeLengths.add(bytes.length);
            byteBufWriteLengths.add(bytes.length);
            output.write(bytes, 0, bytes.length);
        }

        @Override
        public void write(
                byte[] bytes,
                int offset,
                int length) {
            writeLengths.add(length);
            byteArrayWriteLengths.add(length);
            output.write(bytes, offset, length);
        }

        @Override
        public void flush() {
        }

        private byte[] bytes() {
            return output.toByteArray();
        }

        private String ascii() {
            return output.toString(StandardCharsets.US_ASCII);
        }

        private List<Integer> writeLengths() {
            return writeLengths;
        }

        private List<Integer> byteArrayWriteLengths() {
            return byteArrayWriteLengths;
        }

        private List<Integer> byteBufWriteLengths() {
            return byteBufWriteLengths;
        }
    }
}
