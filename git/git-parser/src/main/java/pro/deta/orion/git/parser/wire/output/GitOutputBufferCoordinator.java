package pro.deta.orion.git.parser.wire.output;

import io.netty.buffer.ByteBuf;

import java.util.concurrent.CompletionStage;

public interface GitOutputBufferCoordinator extends AutoCloseable {
    ByteBuf writableBuffer();

    CompletionStage<Void> submitReady();

    CompletionStage<Void> awaitWritable();

    CompletionStage<Void> finish();

    @Override
    void close();
}
