package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;

import java.util.concurrent.CompletionStage;

/**
 * Asynchronous native client write port.
 *
 * <p>The supplied buffer is owned by {@link GitNativeClientOutput}. Its
 * readable bytes must remain immutable until the returned stage completes, then
 * the output releases the buffer. A write implementation that hands the buffer
 * to another ref-counted owner must complete the stage only after that owner no
 * longer uses the bytes.
 */
@FunctionalInterface
public interface GitNativeClientWrite {
    CompletionStage<Void> write(ByteBuf ownedBuffer);
}
