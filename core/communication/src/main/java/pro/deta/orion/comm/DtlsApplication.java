package pro.deta.orion.comm;

import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.comm.common.DtlsSessionEndpoint;
import pro.deta.orion.comm.v3.OrionDTLSAsyncHandler;

import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class DtlsApplication<T> {
    private final AtomicReference<OrionDTLSAsyncHandler<T>> dtlsAsyncHandler = new AtomicReference<>();

    public final void setDtlsAsyncHandler(OrionDTLSAsyncHandler<T> dtlsAsyncHandler) {
        this.dtlsAsyncHandler.set(dtlsAsyncHandler);
    }

    public final void write(DtlsSessionEndpoint<T> target, ByteBuf data) {
        dtlsAsyncHandler.get().applicationRead(target, data);
    }

    public void beforeHandshakeStarted(DtlsSessionEndpoint<T> source) {
        log.info("DTLS handshake started {}", source);
    }

    public void onError(DtlsSessionEndpoint<T> source, String message) {
        log.info("DTLS onError {}:{}", source, message);
    }

    public void afterHandshakeFinished(DtlsSessionEndpoint<T> source) {
        log.info("DTLS handshake finished {}", source);
    }

    public void read(DtlsSessionEndpoint<T> source, ByteBuf data) {
        log.info("DTLS write TO application: {}, {}", source, data.readableBytes());
    }

    public void afterEndpointClosed(DtlsSessionEndpoint<T> source) {
        log.info("DTLS endpoint closed {}", source);
    }
}
