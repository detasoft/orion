package pro.deta.orion.git.parser.wire.sideband;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import pro.deta.orion.git.parser.wire.GitNativeUtils;
import pro.deta.orion.git.parser.wire.GitWireError;
import pro.deta.orion.git.parser.wire.GitWireException;
import pro.deta.orion.git.parser.wire.utils.RawSink;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitSideBandTest {
    private final GitSideBandWriter writer = new GitSideBandWriter(
            UnpooledByteBufAllocator.DEFAULT,
            GitSideBandMode.SIDE_BAND);

    @Test
    void decoderStreamsBandOnePayloadAcrossPacketsAndInputBuffers() {
        RecordingRawTarget target = new RecordingRawTarget();
        List<String> progress = new ArrayList<>();
        GitSideBandDecoder decoder = new GitSideBandDecoder(
                UnpooledByteBufAllocator.DEFAULT,
                GitSideBandMode.SIDE_BAND,
                target,
                progress::add);
        ByteBuf input = sideBandStream(
                packet(GitSideBandBand.DATA, new byte[]{'P', 'A'}),
                packet(GitSideBandBand.PROGRESS, "counting\n".getBytes(StandardCharsets.UTF_8)),
                packet(GitSideBandBand.DATA, new byte[]{'C', 'K'}),
                flush());

        ByteBuf first = input.readRetainedSlice(7);
        ByteBuf second = input.readRetainedSlice(input.readableBytes());
        try {
            decoder.accept(first);
            decoder.accept(second);

            assertThat(target.bytes()).containsExactly((byte) 'P', (byte) 'A', (byte) 'C', (byte) 'K');
            assertThat(target.sliceSizes()).containsExactly(2, 2);
            assertThat(progress).containsExactly("counting\n");
            assertThat(decoder.isComplete()).isTrue();
        } finally {
            decoder.close();
            first.release();
            second.release();
            input.release();
        }
    }

    @Test
    void decoderRejectsUnknownBandId() {
        RecordingRawTarget target = new RecordingRawTarget();
        GitSideBandDecoder decoder = new GitSideBandDecoder(
                UnpooledByteBufAllocator.DEFAULT,
                GitSideBandMode.SIDE_BAND,
                target,
                _progress -> {
                });
        ByteBuf input = sideBandStream(packet((byte) 4, new byte[]{'x'}));

        try {
            assertThatThrownBy(() -> decoder.accept(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.INVALID_SIDE_BAND,
                                    GitWireError.Phase.SIDE_BAND,
                                    0,
                                    4,
                                    "Invalid Git side-band id 4")));
            assertThat(input.readerIndex()).isEqualTo(4);
        } finally {
            decoder.close();
            input.release();
        }
    }

    @Test
    void decoderTurnsFatalBandIntoTypedWireError() {
        RecordingRawTarget target = new RecordingRawTarget();
        GitSideBandDecoder decoder = new GitSideBandDecoder(
                UnpooledByteBufAllocator.DEFAULT,
                GitSideBandMode.SIDE_BAND,
                target,
                _progress -> {
                });
        ByteBuf input = sideBandStream(packet(GitSideBandBand.FATAL, "remote failed\n".getBytes(StandardCharsets.UTF_8)));

        try {
            assertThatThrownBy(() -> decoder.accept(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> {
                        assertThat(error.error().kind()).isEqualTo(GitWireError.Kind.SIDE_BAND_FATAL);
                        assertThat(error.error().phase()).isEqualTo(GitWireError.Phase.SIDE_BAND);
                        assertThat(error.error().packetIndex()).isZero();
                        assertThat(error.error().byteOffset()).isZero();
                        assertThat(error.error().message()).isEqualTo("Remote Git side-band fatal: remote failed");
                    });
        } finally {
            decoder.close();
            input.release();
        }
    }

    @Test
    void decoderRejectsSideBandPacketAboveNegotiatedModeLimit() {
        RecordingRawTarget target = new RecordingRawTarget();
        GitSideBandDecoder decoder = new GitSideBandDecoder(
                UnpooledByteBufAllocator.DEFAULT,
                GitSideBandMode.SIDE_BAND,
                target,
                _progress -> {
                });
        byte[] payload = new byte[GitSideBandMode.SIDE_BAND.maxDataBytesPerPacket() + 1];
        ByteBuf input = sideBandStream(packet(GitSideBandBand.PROGRESS, payload));

        try {
            assertThatThrownBy(() -> decoder.accept(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> {
                        assertThat(error.error().kind()).isEqualTo(GitWireError.Kind.LENGTH_EXCEEDS_LIMIT);
                        assertThat(error.error().phase()).isEqualTo(GitWireError.Phase.SIDE_BAND);
                    });
        } finally {
            decoder.close();
            input.release();
        }
    }

    @Test
    void decoderRejectsReadableInputAfterCloseDuringBufferedPayload() {
        RecordingRawTarget target = new RecordingRawTarget();
        GitSideBandDecoder decoder = new GitSideBandDecoder(
                UnpooledByteBufAllocator.DEFAULT,
                GitSideBandMode.SIDE_BAND,
                target,
                _progress -> {
                });
        ByteBuf input = sideBandStream(packet(GitSideBandBand.PROGRESS, "counting\n".getBytes(StandardCharsets.UTF_8)));
        ByteBuf first = input.readRetainedSlice(7);
        ByteBuf second = input.readRetainedSlice(input.readableBytes());

        try {
            decoder.accept(first);
            decoder.close();

            assertThat(decoder.isComplete()).isTrue();
            assertThatThrownBy(() -> decoder.accept(second))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Git side-band stream is already complete");
        } finally {
            first.release();
            second.release();
            input.release();
        }
    }

    @Test
    void writerSplitsBandOnePayloadAtReferenceGitPacketLimit() {
        ByteBuf payload = repeated('a', GitSideBandMode.SIDE_BAND.maxDataBytesPerPacket() + 1);

        List<ByteBuf> packets = writer.write(GitSideBandBand.DATA, payload);
        try {
            assertThat(payload.readerIndex()).isZero();
            assertThat(packets).hasSize(2);
            assertThat(packetLength(packets.get(0))).isEqualTo(GitSideBandMode.SIDE_BAND.maxPacketLength());
            assertThat(packets.get(0).getByte(4)).isEqualTo((byte) 1);
            assertThat(packets.get(0).readableBytes()).isEqualTo(GitSideBandMode.SIDE_BAND.maxPacketLength());
            assertThat(packetLength(packets.get(1))).isEqualTo(6);
            assertThat(packets.get(1).getByte(4)).isEqualTo((byte) 1);
            assertThat(packets.get(1).getByte(5)).isEqualTo((byte) 'a');
        } finally {
            releaseAll(packets);
            payload.release();
        }
    }

    @Test
    void writerUsesLargeSideBandPacketLimitFor64kMode() {
        GitSideBandWriter largeWriter = new GitSideBandWriter(
                UnpooledByteBufAllocator.DEFAULT,
                GitSideBandMode.SIDE_BAND_64K);
        ByteBuf payload = repeated('b', GitSideBandMode.SIDE_BAND_64K.maxDataBytesPerPacket() + 1);

        List<ByteBuf> packets = largeWriter.write(GitSideBandBand.DATA, payload);
        try {
            assertThat(packets).hasSize(2);
            assertThat(packetLength(packets.get(0))).isEqualTo(GitSideBandMode.SIDE_BAND_64K.maxPacketLength());
            assertThat(packets.get(0).getByte(4)).isEqualTo((byte) 1);
            assertThat(packetLength(packets.get(1))).isEqualTo(6);
        } finally {
            releaseAll(packets);
            payload.release();
        }
    }

    private static ByteBuf sideBandStream(ByteBuf... packets) {
        ByteBuf input = Unpooled.buffer();
        for (ByteBuf packet : packets) {
            try {
                input.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
            } finally {
                packet.release();
            }
        }
        return input;
    }

    private static ByteBuf packet(GitSideBandBand band, byte[] payload) {
        return packet((byte) band.id(), payload);
    }

    private static ByteBuf packet(byte band, byte[] payload) {
        ByteBuf packet = Unpooled.buffer();
        int length = payload.length + 5;
        writeLengthHeader(packet, length);
        packet.writeByte(band);
        packet.writeBytes(payload);
        return packet;
    }

    private static ByteBuf flush() {
        ByteBuf packet = Unpooled.buffer(4, 4);
        writeLengthHeader(packet, 0);
        return packet;
    }

    private static ByteBuf repeated(char value, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return Unpooled.wrappedBuffer(bytes);
    }

    private static int packetLength(ByteBuf packet) {
        return GitNativeUtils.packetLength(packet, packet.readerIndex());
    }

    private static void writeLengthHeader(ByteBuf output, int length) {
        String header = String.format("%04x", length);
        output.writeCharSequence(header, StandardCharsets.US_ASCII);
    }

    private static void releaseAll(List<ByteBuf> packets) {
        for (ByteBuf packet : packets) {
            packet.release();
        }
    }

    private static final class RecordingRawTarget implements RawSink.Target {
        private final List<Byte> bytes = new ArrayList<>();
        private final List<Integer> sliceSizes = new ArrayList<>();

        @Override
        public void accept(ByteBuf input) {
            try {
                sliceSizes.add(input.readableBytes());
                while (input.isReadable()) {
                    bytes.add(input.readByte());
                }
            } finally {
                input.release();
            }
        }

        byte[] bytes() {
            byte[] result = new byte[bytes.size()];
            for (int i = 0; i < bytes.size(); i++) {
                result[i] = bytes.get(i);
            }
            return result;
        }

        List<Integer> sliceSizes() {
            return sliceSizes;
        }
    }
}
