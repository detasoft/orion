package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationRuntime;
import pro.deta.orion.continuation.RuntimeFlow;
import pro.deta.orion.git.parser.wire.continuation.ControlHeaderContinuation;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.Objects;

/**
 * Streaming Git wire facade backed by one flat graph of
 * {@link Continuation Continuations}. The facade owns the runtime while the
 * supplied {@link ByteBuf} remains caller-owned.
 */
public final class GitMinimalWireMachine {
    private final Context context;
    private final ContinuationRuntime<ByteBuf> runtime;

    public GitMinimalWireMachine(
            ByteBufAllocator allocator,
            GitNativeClientOutput clientOutput) {
        this.context = new Context(
                Objects.requireNonNull(allocator, "allocator"),
                Objects.requireNonNull(clientOutput, "clientOutput"));
        this.runtime = new ContinuationRuntime<ByteBuf>(
                new ControlHeaderContinuation(context, ProtocolStage.INITIAL_REQUEST));
    }

    public RuntimeFlow accept(ByteBuf input) {
        return runtime.accept(input);
    }


    public RuntimeFlow resumeTask() {
        return runtime.resumeTask();
    }

    public void close() {
        runtime.close("Git wire machine closed");
    }

    @TestOnly
    public static Context testContext(ByteBufAllocator allocator) {
        return new Context(
                Objects.requireNonNull(allocator, "allocator"),
                new GitNativeClientOutput(
                        allocator.buffer(
                                GitNativeClientOutput.BUFFER_CAPACITY,
                                GitNativeClientOutput.BUFFER_CAPACITY)));
    }

    @TestOnly
    public static Context testContext(
            ByteBufAllocator allocator,
            GitNativeClientOutput clientOutput) {
        return new Context(
                Objects.requireNonNull(allocator, "allocator"),
                Objects.requireNonNull(clientOutput, "clientOutput"));
    }

    public static final class Context {
        public final ByteBufAllocator allocator;
        public final GitNativeClientOutput clientOutput;

        Context(
                ByteBufAllocator allocator,
                GitNativeClientOutput clientOutput) {
            this.allocator = allocator;
            this.clientOutput = clientOutput;
        }
    }
}
