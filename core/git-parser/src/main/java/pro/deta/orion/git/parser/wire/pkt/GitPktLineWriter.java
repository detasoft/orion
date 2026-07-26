package pro.deta.orion.git.parser.wire.pkt;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.git.parser.wire.GitFixedControlFrameReader;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static pro.deta.orion.git.parser.wire.control.ControlState.PKT_LINE_HEADER_SIZE;

public final class GitPktLineWriter {
    private static final byte[] HEX_DIGITS = new byte[]{
            '0', '1', '2', '3',
            '4', '5', '6', '7',
            '8', '9', 'a', 'b',
            'c', 'd', 'e', 'f'
    };
    private static final int MAX_PAYLOAD_LENGTH = GitFixedControlFrameReader.MAX_PKT_LINE_LENGTH - PKT_LINE_HEADER_SIZE;

    private final ByteBufAllocator allocator;

    public GitPktLineWriter(ByteBufAllocator allocator) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
    }

    public ByteBuf writeData(ByteBuf payload) {
        Objects.requireNonNull(payload, "payload");
        int payloadLength = payload.readableBytes();
        ByteBuf packet = allocateDataPacket(payloadLength);
        packet.writeBytes(payload, payload.readerIndex(), payloadLength);
        return packet;
    }

    public ByteBuf writeText(String payload) {
        Objects.requireNonNull(payload, "payload");
        return writeData(payload.getBytes(StandardCharsets.UTF_8));
    }

    public ByteBuf writeTextLine(String payload) {
        Objects.requireNonNull(payload, "payload");
        byte[] text = payload.getBytes(StandardCharsets.UTF_8);
        byte[] line = new byte[text.length + 1];
        System.arraycopy(text, 0, line, 0, text.length);
        line[line.length - 1] = '\n';
        return writeData(line);
    }

    public ByteBuf writeFlush() {
        return writeControlPacket(0);
    }

    public ByteBuf writeDelimiter() {
        return writeControlPacket(1);
    }

    public ByteBuf writeResponseEnd() {
        return writeControlPacket(2);
    }

    private ByteBuf writeData(byte[] payload) {
        ByteBuf packet = allocateDataPacket(payload.length);
        packet.writeBytes(payload);
        return packet;
    }

    private ByteBuf allocateDataPacket(int payloadLength) {
        if (payloadLength > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("Pkt-line payload exceeds Git pkt-line limit");
        }
        int packetLength = payloadLength + PKT_LINE_HEADER_SIZE;
        ByteBuf packet = allocator.buffer(packetLength, packetLength);
        writeLengthHeader(packet, packetLength);
        return packet;
    }

    private ByteBuf writeControlPacket(int packetLength) {
        ByteBuf packet = allocator.buffer(PKT_LINE_HEADER_SIZE, PKT_LINE_HEADER_SIZE);
        writeLengthHeader(packet, packetLength);
        return packet;
    }

    private static void writeLengthHeader(ByteBuf output, int packetLength) {
        output.writeByte(HEX_DIGITS[(packetLength >>> 12) & 0x0f]);
        output.writeByte(HEX_DIGITS[(packetLength >>> 8) & 0x0f]);
        output.writeByte(HEX_DIGITS[(packetLength >>> 4) & 0x0f]);
        output.writeByte(HEX_DIGITS[packetLength & 0x0f]);
    }
}
