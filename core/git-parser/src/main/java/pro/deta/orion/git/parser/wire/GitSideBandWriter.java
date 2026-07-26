package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class GitSideBandWriter {
    private final ByteBufAllocator allocator;
    private final GitPktLineWriter pktLineWriter;
    private final GitSideBandMode mode;

    public GitSideBandWriter(ByteBufAllocator allocator, GitSideBandMode mode) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.pktLineWriter = new GitPktLineWriter(this.allocator);
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
                packets.add(writePacket(band, payload, payloadIndex, chunkLength));
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

    private ByteBuf writePacket(GitSideBandBand band, ByteBuf payload, int payloadIndex, int chunkLength) {
        ByteBuf sideBandPayload = allocator.buffer(chunkLength + 1, chunkLength + 1);
        try {
            sideBandPayload.writeByte(band.id());
            sideBandPayload.writeBytes(payload, payloadIndex, chunkLength);
            return pktLineWriter.writeData(sideBandPayload);
        } finally {
            sideBandPayload.release();
        }
    }
}
