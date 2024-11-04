package pro.deta.orion.comm.common;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.comm.util.DtlsParser;

import java.util.concurrent.atomic.AtomicLong;

@RequiredArgsConstructor
@Getter
public class DataPacket<T> {
    private static final AtomicLong PACKET_COUNTER = new AtomicLong(0);
    private final long packetNo = PACKET_COUNTER.incrementAndGet();
    private final DtlsSessionEndpoint<T> endpoint;
    private final ByteBuf buf;

    public void release() {
        buf.release();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("DataPacket{");
        sb.append("packetNo=").append(packetNo);
        sb.append(", endpoint=").append(endpoint);
        sb.append(", length=").append(buf.readableBytes());
            DtlsParser.DtlsRecord record = DtlsParser.parseRecord(buf);
            if (record != null) {
                sb.append(", record.contentType=").append(record.contentType);
                sb.append(", record.sequenceNumber=").append(record.sequenceNumber);
                sb.append(", record.isHandshake=").append(record.isHandshake());
                sb.append(", record.isPartial=").append(record.isPartial());
            }
        sb.append('}');
        return sb.toString();
    }
}
