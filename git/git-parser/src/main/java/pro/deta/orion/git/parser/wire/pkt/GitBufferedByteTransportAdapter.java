package pro.deta.orion.git.parser.wire.pkt;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static pro.deta.orion.git.parser.wire.GitNativeUtils.hexDigit;
import static pro.deta.orion.git.parser.wire.control.ControlState.MAX_PKT_LINE_LENGTH;
import static pro.deta.orion.git.parser.wire.control.ControlState.PKT_LINE_HEADER_SIZE;

public final class GitBufferedByteTransportAdapter {
    private static final int SIDEBAND_HEADER_SIZE = PKT_LINE_HEADER_SIZE + 1;
    private static final int MAX_SIDEBAND_PAYLOAD_LENGTH =
            MAX_PKT_LINE_LENGTH - SIDEBAND_HEADER_SIZE;

    private final BufferedByteInput input;
    private final BufferedByteOutput output;
    private final GitPktLineWriter pktLineWriter;

    public GitBufferedByteTransportAdapter(
            BufferedByteInput input,
            BufferedByteOutput output) {
        this.input = input;
        this.output = output;
        pktLineWriter = new GitPktLineWriter();
    }

    public ControlState readControlState() throws IOException {
        int headerValue = 0;
        for (int i = 0; i < PKT_LINE_HEADER_SIZE; i++) {
            headerValue = (headerValue << 8) | requireInput().readUnsignedByte();
        }
        Result<ControlState> control = ControlState.readControlType(headerValue);
        if (control instanceof Result.Success<ControlState> success) {
            return success.value();
        }
        Result.Failure<ControlState> failure = (Result.Failure<ControlState>) control;
        throw new IOException("Invalid Git pkt-line header: " + failure.getMessage(), failure.throwable());
    }

    public ByteBuf readPayload(ControlState control) throws IOException {
        Objects.requireNonNull(control, "control");
        return requireInput().readCopy(control.payloadLength());
    }

    public GitPktLine readPacket() throws IOException {
        ControlState control = readControlState();
        return new GitPktLine(control, readPayload(control));
    }

    public void writeData(ByteBuf payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        int payloadLength = payload.readableBytes();
        BufferedByteOutput output = requireOutput();
        output.write(pktLineWriter.writeDataHeader(payloadLength));
        output.write(payload.slice(payload.readerIndex(), payloadLength));
    }

    public void writeText(String payload) throws IOException {
        writeData(utf8(payload));
    }

    public void writeTextLine(String payload) throws IOException {
        byte[] text = utf8(payload);
        byte[] line = new byte[text.length + 1];
        System.arraycopy(text, 0, line, 0, text.length);
        line[line.length - 1] = '\n';
        writeData(line);
    }

    public void writeFlush() throws IOException {
        requireOutput().write(pktLineWriter.writeFlush());
    }

    public void writeDelimiter() throws IOException {
        requireOutput().write(pktLineWriter.writeDelimiter());
    }

    public void writeResponseEnd() throws IOException {
        requireOutput().write(pktLineWriter.writeResponseEnd());
    }

    public void writeSidebandData(ByteBuf payload) throws IOException {
        writeSideband(SidebandChannel.DATA, payload);
    }

    public void writeSidebandProgress(ByteBuf payload) throws IOException {
        writeSideband(SidebandChannel.PROGRESS, payload);
    }

    public void writeSidebandProgress(String payload) throws IOException {
        writeSideband(SidebandChannel.PROGRESS, utf8(payload));
    }

    public void writeSidebandError(ByteBuf payload) throws IOException {
        writeSideband(SidebandChannel.ERROR, payload);
    }

    public void writeSidebandError(String payload) throws IOException {
        writeSideband(SidebandChannel.ERROR, utf8(payload));
    }

    public void flush() throws IOException {
        requireOutput().flush();
    }

    private void writeSideband(
            SidebandChannel channel,
            ByteBuf payload) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(payload, "payload");
        int payloadOffset = payload.readerIndex();
        int remaining = payload.readableBytes();
        BufferedByteOutput output = requireOutput();
        do {
            int chunkLength = Math.min(remaining, MAX_SIDEBAND_PAYLOAD_LENGTH);
            int packetLength = SIDEBAND_HEADER_SIZE + chunkLength;
            output.write(sidebandHeader(packetLength, channel));
            output.write(payload.slice(payloadOffset, chunkLength));
            payloadOffset += chunkLength;
            remaining -= chunkLength;
        } while (remaining > 0);
    }

    private void writeSideband(
            SidebandChannel channel,
            byte[] payload) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(payload, "payload");
        int payloadOffset = 0;
        int remaining = payload.length;
        BufferedByteOutput output = requireOutput();
        do {
            int chunkLength = Math.min(remaining, MAX_SIDEBAND_PAYLOAD_LENGTH);
            int packetLength = SIDEBAND_HEADER_SIZE + chunkLength;
            output.write(sidebandHeader(packetLength, channel));
            output.write(payload, payloadOffset, chunkLength);
            payloadOffset += chunkLength;
            remaining -= chunkLength;
        } while (remaining > 0);
    }

    private static byte[] utf8(String payload) {
        Objects.requireNonNull(payload, "payload");
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    private void writeData(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        BufferedByteOutput output = requireOutput();
        output.write(pktLineWriter.writeDataHeader(payload.length));
        output.write(payload);
    }

    private BufferedByteInput requireInput() {
        if (input == null) {
            throw new IllegalStateException("input is not configured");
        }
        return input;
    }

    private BufferedByteOutput requireOutput() {
        if (output == null) {
            throw new IllegalStateException("output is not configured");
        }
        return output;
    }

    private static void writeLengthHeader(ByteBuf output, int packetLength) {
        output.writeByte(hexDigit((packetLength >>> 12) & 0x0f));
        output.writeByte(hexDigit((packetLength >>> 8) & 0x0f));
        output.writeByte(hexDigit((packetLength >>> 4) & 0x0f));
        output.writeByte(hexDigit(packetLength & 0x0f));
    }

    private static byte[] sidebandHeader(
            int packetLength,
            SidebandChannel channel) {
        return new byte[]{
                hexDigit((packetLength >>> 12) & 0x0f),
                hexDigit((packetLength >>> 8) & 0x0f),
                hexDigit((packetLength >>> 4) & 0x0f),
                hexDigit(packetLength & 0x0f),
                channel.wireValue()
        };
    }

    public record GitPktLine(ControlState control, ByteBuf payload) {
        public GitPktLine {
            Objects.requireNonNull(control, "control");
            Objects.requireNonNull(payload, "payload");
        }
    }

    public enum SidebandChannel {
        DATA(1),
        PROGRESS(2),
        ERROR(3);

        private final byte wireValue;

        SidebandChannel(int wireValue) {
            this.wireValue = (byte) wireValue;
        }

        public byte wireValue() {
            return wireValue;
        }
    }
}
