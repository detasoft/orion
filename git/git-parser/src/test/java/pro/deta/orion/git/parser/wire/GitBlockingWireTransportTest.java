package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.wire.control.ControlState.MAX_PKT_LINE_LENGTH;

class GitBlockingWireTransportTest {
    private static final String MAIN_ID =
            "1111111111111111111111111111111111111111";
    private static final String TAG_ID =
            "2222222222222222222222222222222222222222";
    private static final String PEELED_TAG_ID =
            "3333333333333333333333333333333333333333";
    private final ByteBufAllocator allocator = UnpooledByteBufAllocator.DEFAULT;

    @Test
    void doesNotKeepReusableOutputBufferOnTransport() {
        assertThat(GitBlockingWireTransport.class.getDeclaredFields())
                .filteredOn(field -> !Modifier.isStatic(field.getModifiers()))
                .filteredOn(field -> ByteBuf.class.equals(field.getType()))
                .isEmpty();
    }

    @Test
    void readsPktLineControlAndPayloadFromBufferedInput() throws Exception {
        GitBlockingWireTransport transport = input("000ahello\n0000");

        ControlState data = transport.readControlState();
        ByteBuf payload = transport.readPayload(data);
        try {
            assertThat(data.type()).isEqualTo(ControlState.ControlType.DATA);
            assertThat(payload.toString(StandardCharsets.UTF_8))
                    .isEqualTo("hello\n");

            ControlState flush = transport.readControlState();
            assertThat(flush.type()).isEqualTo(ControlState.ControlType.FLUSH);
            assertThat(flush.payloadLength()).isZero();
        } finally {
            payload.release();
        }
    }

    @Test
    void rejectsMalformedPktLineHeaderFromBufferedInput() {
        GitBlockingWireTransport transport = input("zzzz");

        assertThatThrownBy(transport::readControlState)
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
            transport.writeFlush();
            transport.flush();

            assertThat(sink.writeLengths()).containsExactly(4, 5, 4);
            assertThat(sink.byteArrayWriteLengths()).containsExactly(4, 4);
            assertThat(sink.byteBufWriteLengths()).containsExactly(5);
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
                List.of(GitCapability.MULTI_ACK),
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

    @Test
    void writesSimpleSerializationToByteArrayOutput() throws Exception {
        ByteArrayRecordingOutput sink = new ByteArrayRecordingOutput();
        GitBlockingWireTransport output = output(sink);

        output.sendNak();

        assertThat(sink.ascii()).isEqualTo("0008NAK\n");
    }

    @Test
    void streamsLegacyPackToByteArrayOutput() throws Exception {
        ByteArrayRecordingOutput sink = new ByteArrayRecordingOutput();
        GitBlockingWireTransport output = output(sink);
        GitBlockingWireTransport.LegacyPackResponse response =
                output.beginLegacyPack(producer("PACK"), true);

        response.advance();

        assertThat(sink.ascii()).isEqualTo("0008NAK\nPACK");
    }

    @Test
    void writesLegacySideBandResponse() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GitBlockingWireTransport output = output(
                new OutputStreamBufferedByteOutput(bytes));
        GitBlockingWireTransport.LegacySideBandResponse response =
                output.beginLegacySideBand64k(
                        producer("PACK-data"),
                        true);

        try {
            response.advance();

            assertThat(new String(bytes.toByteArray(), StandardCharsets.US_ASCII))
                    .isEqualTo(
                            "0008NAK\n"
                                    + "000e\u0001PACK-data"
                                    + "0000");
        } finally {
            response.close();
        }
    }

    @Test
    void rejectsProducerThatMakesNoProgress() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GitBlockingWireTransport output = output(
                new OutputStreamBufferedByteOutput(bytes));
        GitBlockingWireTransport.LegacyPackResponse response =
                output.beginLegacyPack(new NativePackProducer() {
                    @Override
                    public Result produce(ByteBuf destination) {
                        return Result.MORE;
                    }

                    @Override
                    public void close() {
                    }
                }, true);

        try {
            assertThatThrownBy(response::advance)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Native pack producer made no progress");
        } finally {
            response.close();
        }
    }

    @Test
    void allowsOutputAfterCompletedPackResponse() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GitBlockingWireTransport output = output(
                new OutputStreamBufferedByteOutput(bytes));
        GitBlockingWireTransport.LegacyPackResponse response =
                output.beginLegacyPack(producer("PACK"), true);

        response.advance();

        assertThatCode(output::sendNak).doesNotThrowAnyException();
        assertThat(new String(bytes.toByteArray(), StandardCharsets.US_ASCII))
                .isEqualTo("0008NAK\nPACK0008NAK\n");
    }

    private static NativePackProducer producer(String value) {
        return new TrackingProducer(value);
    }

    private static GitBlockingWireTransport input(String ascii) {
        return new GitBlockingWireTransport(
                new ArrayInput(ascii.getBytes(StandardCharsets.US_ASCII)),
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

    private static final class ArrayInput implements BufferedByteInput {
        private final byte[] bytes;
        private int offset;

        private ArrayInput(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int available() {
            return bytes.length - offset;
        }

        @Override
        public int readUnsignedByte() throws IOException {
            if (offset == bytes.length) {
                throw new EOFException("test input exhausted");
            }
            return bytes[offset++] & 0xff;
        }

        @Override
        public ByteBuf readCopy(int length, ByteBufAllocator allocator) throws IOException {
            if (length < 0) {
                throw new IllegalArgumentException(
                        "length must be non-negative");
            }
            if (available() < length) {
                throw new EOFException("test input exhausted");
            }
            ByteBuf copy = Unpooled.buffer(length, length);
            copy.writeBytes(bytes, offset, length);
            offset += length;
            return copy;
        }

        @Override
        public int readInto(
                ByteBuf target,
                int maxLength) throws IOException {
            int copied = Math.min(
                    Math.min(maxLength, target.writableBytes()),
                    available());
            target.writeBytes(bytes, offset, copied);
            offset += copied;
            return copied;
        }
    }

    private static final class ByteArrayRecordingOutput
            implements BufferedByteOutput {
        private final ByteArrayOutputStream bytes =
                new ByteArrayOutputStream();

        @Override
        public void write(byte[] source, int offset, int length) {
            bytes.write(source, offset, length);
        }

        @Override
        public void write(ByteBuf buffer) {
            throw new AssertionError(
                    "Simple serialization should use byte-array writes");
        }

        @Override
        public void flush() {
        }

        private String ascii() throws IOException {
            return bytes.toString(StandardCharsets.US_ASCII);
        }
    }

    private static final class TrackingProducer implements NativePackProducer {
        private final byte[] value;
        private int offset;
        private boolean closed;

        private TrackingProducer(String value) {
            this.value = value.getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public Result produce(ByteBuf destination) {
            int length = Math.min(
                    destination.writableBytes(),
                    value.length - offset);
            destination.writeBytes(value, offset, length);
            offset += length;
            return offset == value.length ? Result.COMPLETED : Result.MORE;
        }

        @Override
        public Result produce(BufferedByteOutput destination)
                throws IOException {
            int length = Math.min(8 * 1024, value.length - offset);
            destination.write(value, offset, length);
            offset += length;
            return offset == value.length ? Result.COMPLETED : Result.MORE;
        }

        @Override
        public void close() {
            closed = true;
        }
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
