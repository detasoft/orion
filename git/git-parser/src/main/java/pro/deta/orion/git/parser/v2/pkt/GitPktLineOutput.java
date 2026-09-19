package pro.deta.orion.git.parser.v2.pkt;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.util.Objects;

public final class GitPktLineOutput implements BufferedByteOutput {
    private final BufferedByteOutput output;
    private final SideBand sideBand;
    private final int payloadLimit;

    public GitPktLineOutput(BufferedByteOutput output, SideBand sideBand, int packetLimit) {
        this.output = Objects.requireNonNull(output, "output");
        this.sideBand = Objects.requireNonNull(sideBand, "sideBand");
        int headerLength = GitPktLine.PKT_LINE_HEADER_SIZE + (sideBand == SideBand.NONE ? 0 : 1);
        if (packetLimit <= headerLength || packetLimit > GitPktLine.MAX_PKT_LINE_LENGTH) {
            throw new IllegalArgumentException("Invalid packet limit");
        }
        payloadLimit = packetLimit - headerLength;
    }

    @Override
    public void write(ByteBuf buffer) throws IOException {
        int offset = buffer.readerIndex();
        int remaining = buffer.readableBytes();
        while (remaining > 0) {
            int count = Math.min(remaining, payloadLimit);
            GitPktLine.writeDataTo(output, buffer.slice(offset, count), sideBand);
            offset += count;
            remaining -= count;
        }
    }

    @Override
    public void flush() throws IOException {
        output.flush();
    }
}
