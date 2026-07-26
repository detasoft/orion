package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class GitSideBandWriter {
    private static final byte[] HEX_DIGITS = new byte[]{
            '0', '1', '2', '3',
            '4', '5', '6', '7',
            '8', '9', 'a', 'b',
            'c', 'd', 'e', 'f'
    };

    private final ByteBufAllocator allocator;
    private final GitSideBandMode mode;

    public GitSideBandWriter(ByteBufAllocator allocator, GitSideBandMode mode) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public List<ByteBuf> write(GitSideBandBand band, ByteBuf payload) {
        Objects.requireNonNull(band, "band");
        Objects.requireNonNull(payload, "payload");

        List<ByteBuf> packets = new ArrayList<>();
        int payloadIndex = payload.readerIndex();
        int remaining = payload.readableBytes();
        try {
            while (remaining > 0) {
                int chunkLength = Math.min(remaining, mode.maxDataBytesPerPacket());
                ByteBuf packet = allocator.buffer(chunkLength + 5, chunkLength + 5);
                writeLengthHeader(packet, chunkLength + 5);
                packet.writeByte(band.id());
                packet.writeBytes(payload, payloadIndex, chunkLength);
                packets.add(packet);
                payloadIndex += chunkLength;
                remaining -= chunkLength;
            }
            return List.copyOf(packets);
        } catch (RuntimeException | Error e) {
            for (ByteBuf packet : packets) {
                packet.release();
            }
            throw e;
        }
    }

    private static void writeLengthHeader(ByteBuf output, int packetLength) {
        output.writeByte(HEX_DIGITS[(packetLength >>> 12) & 0x0f]);
        output.writeByte(HEX_DIGITS[(packetLength >>> 8) & 0x0f]);
        output.writeByte(HEX_DIGITS[(packetLength >>> 4) & 0x0f]);
        output.writeByte(HEX_DIGITS[packetLength & 0x0f]);
    }
}
