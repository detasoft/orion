package pro.deta.orion.comm.app;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.comm.ProcessorState;
import pro.deta.orion.comm.common.DtlsSessionEndpoint;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class HelloDtlsApplication extends StateDtlsApplication {
    private final AtomicReference<String> message = new AtomicReference<>();

    @Override
    public void afterHandshakeFinished(DtlsSessionEndpoint<InetSocketAddress> endpoint) {
        set(ProcessorState.INIT_COMPLETED);
        log.info("DTLS handshake finished {}", endpoint);
        write(endpoint, Unpooled.copiedBuffer(getOriginalMessage().getBytes(StandardCharsets.UTF_8)));
        set(ProcessorState.WRITE_COMPLETED);
    }

    @Override
    public void read(DtlsSessionEndpoint<InetSocketAddress> source, ByteBuf data) {
        String input = data.readCharSequence(data.readableBytes(), StandardCharsets.UTF_8).toString();
        log.info("[dtls-hello-app] read : {}, {}", source, input);
        message.set(input);
        set(ProcessorState.READ_COMPLETED);
    }

    public String getMessage() {
        return message.get();
    }

    public String getOriginalMessage() {
        return "Yabbadabbadoo";
    }
}