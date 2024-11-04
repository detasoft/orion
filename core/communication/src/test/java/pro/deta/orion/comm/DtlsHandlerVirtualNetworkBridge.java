package pro.deta.orion.comm;

import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.comm.common.DataPacket;
import pro.deta.orion.comm.common.DtlsSessionEndpoint;
import pro.deta.orion.comm.util.DtlsParser;
import pro.deta.orion.comm.v3.OrionDTLSAsyncHandler;

import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
public class DtlsHandlerVirtualNetworkBridge {
    private final Queue<DataPacket<InetSocketAddress>> fromToQueue = new ConcurrentLinkedQueue<>();
    private final InetSocketAddress localAddress;
    private final OrionDTLSAsyncHandler<InetSocketAddress> from;
    private final OrionDTLSAsyncHandler<InetSocketAddress> to;

    public DtlsHandlerVirtualNetworkBridge(InetSocketAddress localAddress, OrionDTLSAsyncHandler<InetSocketAddress> from, OrionDTLSAsyncHandler<InetSocketAddress> to) {
        this.from = from;
        this.to = to;
        this.localAddress = localAddress;
        from.setNetworkConsumer((address, data) -> {
            insightIntoDtls(data);
            fromToQueue.add(new DataPacket<>(new DtlsSessionEndpoint<>(address, localAddress), data));
            to.channelRead(address, localAddress, data);
            // no need to release in tests: the view should be released by the channelRead
            // data.release();
        });
        from.setOnNeedUnwrap((endpoint -> to.process()));
    }

    private void insightIntoDtls(ByteBuf bb) {
        DtlsParser.DtlsRecord record = DtlsParser.parseRecord(bb.slice());
        log.debug("DTLS record: {}", record);
    }
}