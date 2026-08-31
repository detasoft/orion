package pro.deta.orion.git.parser.wire.continuation;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.RecordingBufferedByteOutput;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;

import static org.assertj.core.api.Assertions.assertThat;

abstract class ByteBufContinuationTest {
    protected static GitMinimalWireMachine.Context context() {
        ByteBuf outbound = UnpooledByteBufAllocator.DEFAULT.buffer(
                GitNativeClientOutput.BUFFER_CAPACITY,
                GitNativeClientOutput.BUFFER_CAPACITY);
        return GitMinimalWireMachine.testContext(
                new GitNativeClientOutput(new RecordingBufferedByteOutput(outbound)),
                new InMemoryNativeGitRepositoryProvider(),
                GitNativeRepositoryAccessHook.ALLOW_ALL);
    }

    protected static ContinuationFlow<ByteBuf> processOneByteAtATime(
            Continuation<ByteBuf> continuation,
            ByteBuf input) {
        ContinuationFlow<ByteBuf> flow = null;
        while (input.isReadable()) {
            boolean lastByte = input.readableBytes() == 1;
            flow = process(
                    continuation,
                    input.readRetainedSlice(1));
            if (!lastByte) {
                assertThat(flow).isInstanceOf(ContinuationFlow.Await.class);
            }
        }
        return flow;
    }

    protected static ContinuationFlow<ByteBuf> process(
            Continuation<ByteBuf> continuation,
            ByteBuf input) {
        try {
            return continuation.process(input);
        } finally {
            input.release();
        }
    }

    protected static Continuation<ByteBuf> transitionedTo(
            ContinuationFlow<ByteBuf> flow) {
        assertThat(flow).isInstanceOf(ContinuationFlow.Transition.class);
        return ((ContinuationFlow.Transition<ByteBuf>) flow).next();
    }

    protected static void assertCompletedError(
            ContinuationFlow<ByteBuf> flow,
            String message) {
        assertThat(transitionedTo(flow))
                .isInstanceOfSatisfying(
                        Continuation.CompletedError.class,
                        error -> assertThat(error.throwable())
                                .hasMessageContaining(message));
    }
}
