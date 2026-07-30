package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationRuntime;
import pro.deta.orion.continuation.RuntimeFlow;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
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
        this(
                allocator,
                clientOutput,
                new InMemoryNativeGitRepositoryProvider());
    }

    public GitMinimalWireMachine(
            ByteBufAllocator allocator,
            GitNativeClientOutput clientOutput,
            InMemoryNativeGitRepositoryProvider repositoryProvider) {
        this(
                allocator,
                clientOutput,
                repositoryProvider,
                GitWireConfiguration.allSupported());
    }

    public GitMinimalWireMachine(
            ByteBufAllocator allocator,
            GitNativeClientOutput clientOutput,
            InMemoryNativeGitRepositoryProvider repositoryProvider,
            GitWireConfiguration configuration) {
        this.context = new Context(
                Objects.requireNonNull(allocator, "allocator"),
                Objects.requireNonNull(clientOutput, "clientOutput"),
                new GitNativeRepositoryService(
                        Objects.requireNonNull(
                                repositoryProvider,
                                "repositoryProvider")),
                Objects.requireNonNull(configuration, "configuration"));
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
                                GitNativeClientOutput.BUFFER_CAPACITY)),
                new GitNativeRepositoryService(
                        new InMemoryNativeGitRepositoryProvider()),
                GitWireConfiguration.allSupported());
    }

    @TestOnly
    public static Context testContext(
            ByteBufAllocator allocator,
            GitNativeClientOutput clientOutput) {
        return testContext(
                allocator,
                clientOutput,
                new GitNativeRepositoryService(
                        new InMemoryNativeGitRepositoryProvider()),
                GitWireConfiguration.allSupported());
    }

    @TestOnly
    public static Context testContext(
            ByteBufAllocator allocator,
            GitNativeClientOutput clientOutput,
            InMemoryNativeGitRepositoryProvider repositoryProvider) {
        return testContext(
                allocator,
                clientOutput,
                new GitNativeRepositoryService(repositoryProvider),
                GitWireConfiguration.allSupported());
    }

    @TestOnly
    public static Context testContext(
            ByteBufAllocator allocator,
            GitNativeClientOutput clientOutput,
            GitNativeRepositoryService repositoryService) {
        return testContext(
                allocator,
                clientOutput,
                repositoryService,
                GitWireConfiguration.allSupported());
    }

    @TestOnly
    public static Context testContext(
            ByteBufAllocator allocator,
            GitNativeClientOutput clientOutput,
            GitNativeRepositoryService repositoryService,
            GitWireConfiguration configuration) {
        return new Context(
                Objects.requireNonNull(allocator, "allocator"),
                Objects.requireNonNull(clientOutput, "clientOutput"),
                Objects.requireNonNull(
                        repositoryService,
                        "repositoryService"),
                Objects.requireNonNull(configuration, "configuration"));
    }

    public static final class Context {
        public final ByteBufAllocator allocator;
        public final GitNativeClientOutput clientOutput;
        public final GitNativeRepositoryService repositoryService;
        public final GitWireConfiguration configuration;

        Context(
                ByteBufAllocator allocator,
                GitNativeClientOutput clientOutput,
                GitNativeRepositoryService repositoryService,
                GitWireConfiguration configuration) {
            this.allocator = allocator;
            this.clientOutput = clientOutput;
            this.repositoryService = repositoryService;
            this.configuration = configuration;
        }
    }
}
