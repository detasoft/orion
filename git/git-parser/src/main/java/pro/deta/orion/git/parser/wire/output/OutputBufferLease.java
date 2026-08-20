package pro.deta.orion.git.parser.wire.output;

import io.netty.buffer.ByteBuf;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

public record OutputBufferLease(
        ByteBuf buffer,
        CompletionStage<Void> completion) {
    public OutputBufferLease {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(completion, "completion");
    }
}
