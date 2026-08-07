package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;

import java.util.concurrent.CompletionStage;

/**
 * Asynchronous native client write port.
 *
 * <p>The supplied buffer is owned by the output coordinator. Its readable bytes
 * must remain immutable until the returned stage completes, then the
 * coordinator may reclaim and reuse the buffer.
 */
@FunctionalInterface
public interface GitNativeClientWrite {
    CompletionStage<Void> write(ByteBuf ownedBuffer);
}
