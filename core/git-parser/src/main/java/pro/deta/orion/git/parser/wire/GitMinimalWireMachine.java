package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationRuntime;
import pro.deta.orion.continuation.RuntimeFlow;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.wire.continuation.ControlHeaderContinuation;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.Objects;
import java.util.Optional;

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
            GitNativeClientOutput clientOutput,
            NativeGitRepositoryProvider repositoryProvider,
            GitNativeRepositoryAccessHook accessHook) {
        this(
                allocator,
                clientOutput,
                repositoryProvider,
                accessHook,
                GitWireConfiguration.allSupported());
    }

    public GitMinimalWireMachine(
            ByteBufAllocator allocator,
            GitNativeClientOutput clientOutput,
            NativeGitRepositoryProvider repositoryProvider,
            GitNativeRepositoryAccessHook accessHook,
            GitWireConfiguration configuration) {
        this(
                allocator,
                clientOutput,
                repositoryProvider,
                accessHook,
                configuration,
                NativePackfileUriSourceFactory.NONE);
    }

    public GitMinimalWireMachine(
            ByteBufAllocator allocator,
            GitNativeClientOutput clientOutput,
            NativeGitRepositoryProvider repositoryProvider,
            GitNativeRepositoryAccessHook accessHook,
            GitWireConfiguration configuration,
            NativePackfileUriSourceFactory packfileUriSourceFactory) {
        this.context = new Context(
                Objects.requireNonNull(allocator, "allocator"),
                Objects.requireNonNull(clientOutput, "clientOutput"),
                new GitNativeRepositoryService(
                        Objects.requireNonNull(
                                repositoryProvider,
                                "repositoryProvider"),
                        Objects.requireNonNull(
                                accessHook,
                                "accessHook"),
                        Objects.requireNonNull(
                                configuration,
                                "configuration"),
                        Objects.requireNonNull(
                                packfileUriSourceFactory,
                                "packfileUriSourceFactory")),
                Objects.requireNonNull(configuration, "configuration"));
        this.runtime = new ContinuationRuntime<ByteBuf>(
                new ControlHeaderContinuation(context, ProtocolStage.INITIAL_REQUEST));
    }

    GitMinimalWireMachine(
            Context context,
            Continuation<ByteBuf> initial) {
        this.context = Objects.requireNonNull(context, "context");
        this.runtime = new ContinuationRuntime<>(
                Objects.requireNonNull(initial, "initial"));
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

    public boolean terminal() {
        return runtime.terminal();
    }

    public Optional<Continuation.CompletedError<ByteBuf>> terminalError() {
        return runtime.terminalError();
    }

    @TestOnly
    public static Context testContext(
            ByteBufAllocator allocator,
            GitNativeClientOutput clientOutput,
            NativeGitRepositoryProvider repositoryProvider,
            GitNativeRepositoryAccessHook accessHook) {
        return testContext(
                allocator,
                clientOutput,
                repositoryProvider,
                accessHook,
                GitWireConfiguration.allSupported());
    }

    @TestOnly
    public static Context testContext(
            ByteBufAllocator allocator,
            GitNativeClientOutput clientOutput,
            GitNativeRepositoryService repositoryService) {
        return new Context(
                Objects.requireNonNull(allocator, "allocator"),
                Objects.requireNonNull(clientOutput, "clientOutput"),
                Objects.requireNonNull(
                        repositoryService,
                        "repositoryService"),
                repositoryService.configuration());
    }

    @TestOnly
    public static Context testContext(
            ByteBufAllocator allocator,
            GitNativeClientOutput clientOutput,
            NativeGitRepositoryProvider repositoryProvider,
            GitNativeRepositoryAccessHook accessHook,
            GitWireConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return new Context(
                Objects.requireNonNull(allocator, "allocator"),
                Objects.requireNonNull(clientOutput, "clientOutput"),
                new GitNativeRepositoryService(
                        Objects.requireNonNull(
                                repositoryProvider,
                                "repositoryProvider"),
                        Objects.requireNonNull(
                                accessHook,
                                "accessHook"),
                        configuration),
                configuration);
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
