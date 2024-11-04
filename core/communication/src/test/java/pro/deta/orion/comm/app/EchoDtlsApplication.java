package pro.deta.orion.comm.app;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.comm.ProcessorState;
import pro.deta.orion.comm.common.DtlsSessionEndpoint;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

@Slf4j
public class EchoDtlsApplication extends StateDtlsApplication {
    public static final String HELLO = "Hello ";

    @Override
    public void afterHandshakeFinished(DtlsSessionEndpoint<InetSocketAddress> endpoint) {
        set(ProcessorState.INIT_COMPLETED);
        log.info("DTLS handshake finished {}", endpoint);
    }

    @Override
    public void read(DtlsSessionEndpoint<InetSocketAddress> source, ByteBuf data) {
        set(ProcessorState.READ_COMPLETED);
        String input = data.readCharSequence(data.readableBytes(), StandardCharsets.UTF_8).toString();
        log.info("[dtls-echo-app] read : {}, {}", source, input);
        write(source, Unpooled.copiedBuffer(makeResponse(input).getBytes(StandardCharsets.UTF_8)));
        set(ProcessorState.WRITE_COMPLETED);
    }

    public String makeResponse(String message) {
        return HELLO + message;
    }
}