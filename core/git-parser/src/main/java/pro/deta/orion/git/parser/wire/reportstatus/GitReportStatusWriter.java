package pro.deta.orion.git.parser.wire.reportstatus;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class GitReportStatusWriter {
    public List<ByteBuf> write(GitPktLineWriter pktLineWriter, GitReportStatus status) {
        Objects.requireNonNull(pktLineWriter, "pktLineWriter");
        Objects.requireNonNull(status, "status");
        List<ByteBuf> packets = new ArrayList<>();
        try {
            packets.add(pktLineWriter.writeTextLine(unpackLine(status)));
            for (GitReportStatusRef ref : status.refs()) {
                packets.add(pktLineWriter.writeTextLine(refLine(ref)));
            }
            packets.add(pktLineWriter.writeFlush());
            return List.copyOf(packets);
        } catch (RuntimeException error) {
            release(packets);
            throw error;
        }
    }

    private static String unpackLine(GitReportStatus status) {
        if (status.unpackOk()) {
            return "unpack ok";
        }
        return "unpack " + status.unpackError().orElseThrow();
    }

    private static String refLine(GitReportStatusRef ref) {
        Objects.requireNonNull(ref, "ref");
        return switch (ref.status()) {
            case OK -> "ok " + ref.refName();
            case NG -> "ng " + ref.refName() + ' ' + ref.reason().orElseThrow();
        };
    }

    private static void release(List<ByteBuf> packets) {
        for (ByteBuf packet : packets) {
            packet.release();
        }
    }
}
