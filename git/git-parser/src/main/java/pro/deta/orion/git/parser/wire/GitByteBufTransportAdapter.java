package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.continuation.ContinuationTask;
import pro.deta.orion.continuation.RuntimeFlow;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.continuation.ControlHeaderContinuation;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static pro.deta.orion.git.parser.wire.GitNativeUtils.hexDigit;

public final class GitByteBufTransportAdapter {
    public static final int DEFAULT_INPUT_BUFFER_SIZE = 16 * 1024;

    private final ByteBufAllocator allocator;
    private final NativeGitRepositoryProvider repositoryProvider;
    private final GitNativeRepositoryAccessHook accessHook;
    private final GitWireConfiguration configuration;
    private final NativePackfileUriSourceFactory packfileUriSourceFactory;
    private final int inputBufferSize;

    public GitByteBufTransportAdapter(
            ByteBufAllocator allocator,
            NativeGitRepositoryProvider repositoryProvider,
            GitNativeRepositoryAccessHook accessHook,
            GitWireConfiguration configuration,
            NativePackfileUriSourceFactory packfileUriSourceFactory) {
        this(
                allocator,
                repositoryProvider,
                accessHook,
                configuration,
                packfileUriSourceFactory,
                DEFAULT_INPUT_BUFFER_SIZE);
    }

    public GitByteBufTransportAdapter(
            ByteBufAllocator allocator,
            NativeGitRepositoryProvider repositoryProvider,
            GitNativeRepositoryAccessHook accessHook,
            GitWireConfiguration configuration,
            NativePackfileUriSourceFactory packfileUriSourceFactory,
            int inputBufferSize) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.repositoryProvider = Objects.requireNonNull(
                repositoryProvider,
                "repositoryProvider");
        this.accessHook = Objects.requireNonNull(accessHook, "accessHook");
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration");
        this.packfileUriSourceFactory = Objects.requireNonNull(
                packfileUriSourceFactory,
                "packfileUriSourceFactory");
        if (inputBufferSize <= 0) {
            throw new IllegalArgumentException(
                    "inputBufferSize must be positive");
        }
        this.inputBufferSize = inputBufferSize;
    }

    public void advertise(
            InitialRequestData data,
            BufferedByteOutput output)
            throws IOException {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(output, "output");
        try (StreamSession session = commandSession(output)) {
            drive(session.machine(), initialRequest(data));
        }
    }

    public void serveCommand(
            InitialRequestData data,
            BufferedByteInput input,
            BufferedByteOutput output)
            throws IOException {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        try (StreamSession session = commandSession(output)) {
            drive(session.machine(), initialRequest(data));
            pump(session.machine(), input);
        }
    }

    public void serveSmartHttpPost(
            InitialRequestData data,
            BufferedByteInput input,
            BufferedByteOutput output)
            throws IOException {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        try (StreamSession session = smartHttpPostSession(
                data,
                output)) {
            prime(session.machine());
            pump(session.machine(), input);
        }
    }

    private StreamSession commandSession(BufferedByteOutput output) {
        SessionContext sessionContext = sessionContext(output);
        return new StreamSession(
                new GitMinimalWireMachine(
                        sessionContext.context(),
                        new ControlHeaderContinuation(
                                sessionContext.context(),
                                ProtocolStage.INITIAL_REQUEST)));
    }

    private StreamSession smartHttpPostSession(
            InitialRequestData data,
            BufferedByteOutput output) {
        SessionContext sessionContext = sessionContext(output);
        GitMinimalWireMachine.Context context = sessionContext.context();
        return new StreamSession(
                new GitMinimalWireMachine(
                        context,
                        smartHttpPostContinuation(context, data)));
    }

    private SessionContext sessionContext(BufferedByteOutput output) {
        GitNativeClientOutput clientOutput = new GitNativeClientOutput(
                output);
        return new SessionContext(
                new GitMinimalWireMachine.Context(
                        allocator,
                        clientOutput,
                        new GitNativeRepositoryService(
                                repositoryProvider,
                                accessHook,
                                configuration,
                                packfileUriSourceFactory),
                        configuration));
    }

    private record SessionContext(
            GitMinimalWireMachine.Context context) {
    }

    private record StreamSession(
            GitMinimalWireMachine machine) implements AutoCloseable {

        @Override
        public void close() {
            machine.close();
        }
    }

    private Continuation<ByteBuf> smartHttpPostContinuation(
            GitMinimalWireMachine.Context context,
            InitialRequestData data) {
        InitialRequestData.ProtocolVersion version =
                data.getProtocolVersion().orElse(null);
        if (data.getService() == InitialRequestService.UPLOAD_PACK) {
            if (version == InitialRequestData.ProtocolVersion.V2) {
                return pro.deta.orion.git.parser.wire.continuation.v2
                        .UploadPackContinuation.afterAdvertisement(
                                context,
                                data);
            }
            GitV1Advertisement advertisement =
                    context.repositoryService.legacyUploadPackAdvertisement(
                            data);
            return pro.deta.orion.git.parser.wire.continuation.v0v1
                    .UploadPackContinuation.afterAdvertisement(
                            context,
                            data,
                            advertisement);
        }
        if (version == InitialRequestData.ProtocolVersion.V2) {
            throw new IllegalArgumentException(
                    "Protocol v2 receive-pack is not supported");
        }
        GitV1Advertisement advertisement =
                context.repositoryService.legacyReceivePackAdvertisement(data);
        return pro.deta.orion.git.parser.wire.continuation.v0v1
                .ReceivePackContinuation.afterAdvertisement(
                        context,
                        data,
                        advertisement);
    }

    private void prime(GitMinimalWireMachine machine) throws IOException {
        drive(machine, allocator.buffer(0, 0));
    }

    private void pump(
            GitMinimalWireMachine machine,
            BufferedByteInput input)
            throws IOException {
        while (!machine.terminal()) {
            ByteBuf buffer = allocator.buffer(inputBufferSize, inputBufferSize);
            int read;
            try {
                read = input.readInto(buffer, inputBufferSize);
            } catch (Throwable error) {
                buffer.release();
                throw error;
            }
            if (read == 0) {
                buffer.release();
                return;
            }
            drive(machine, buffer);
        }
    }

    private void drive(
            GitMinimalWireMachine machine,
            ByteBuf input)
            throws IOException {
        try {
            RuntimeFlow flow = machine.accept(input);
            handleFlow(machine, flow);
        } finally {
            input.release();
        }
    }

    private void handleFlow(
            GitMinimalWireMachine machine,
            RuntimeFlow flow)
            throws IOException {
        RuntimeFlow current = flow;
        while (true) {
            switch (current) {
                case ContinuationFlow.Yield<?> yield -> {
                    runTask(yield.task());
                    current = machine.resumeTask();
                }
                case RuntimeFlow.Error error ->
                        throw ioFailure(error.message(), error.throwable());
                case RuntimeFlow.Terminal ignored -> {
                    terminalError(machine);
                    return;
                }
                case ContinuationFlow.Await<?> ignored -> {
                    return;
                }
            }
        }
    }

    private static void runTask(Runnable task) throws IOException {
        try {
            task.run();
            ContinuationTask.completionOf(task)
                    .toCompletableFuture()
                    .join();
        } catch (UncheckedIOException error) {
            throw error.getCause();
        } catch (CompletionException error) {
            throw ioFailure("Git wire task failed", error.getCause());
        } catch (RuntimeException error) {
            throw ioFailure("Git wire task failed", error);
        }
    }

    private static void terminalError(
            GitMinimalWireMachine machine)
            throws IOException {
        var error = machine.terminalError();
        if (error.isPresent()) {
            throw ioFailure(error.get().message(), error.get().throwable());
        }
    }

    private static IOException ioFailure(
            String message,
            Throwable throwable) {
        if (throwable instanceof IOException ioException) {
            return ioException;
        }
        return new IOException(message, throwable);
    }

    private ByteBuf initialRequest(InitialRequestData data) {
        byte[] payload = initialRequestPayload(data);
        int packetLength = ControlState.PKT_LINE_HEADER_SIZE + payload.length;
        if (packetLength > ControlState.MAX_PKT_LINE_LENGTH) {
            throw new IllegalArgumentException(
                    "Initial Git service request exceeds pkt-line limit");
        }
        ByteBuf buffer = allocator.buffer(packetLength, packetLength);
        buffer.writeByte(hexDigit((packetLength >>> 12) & 0x0f));
        buffer.writeByte(hexDigit((packetLength >>> 8) & 0x0f));
        buffer.writeByte(hexDigit((packetLength >>> 4) & 0x0f));
        buffer.writeByte(hexDigit(packetLength & 0x0f));
        buffer.writeBytes(payload);
        return buffer;
    }

    private static byte[] initialRequestPayload(InitialRequestData data) {
        StringBuilder builder = new StringBuilder()
                .append(data.getService().wireName())
                .append(' ')
                .append(data.getRepositoryPath())
                .append('\0');
        String host = data.getHost();
        boolean hasHost = host != null && !host.isBlank();
        boolean hasParameters = !data.getParameters().isEmpty();
        if (hasHost) {
            builder.append("host=")
                    .append(host)
                    .append('\0');
        }
        if (hasHost || hasParameters) {
            builder.append('\0');
        }
        for (Map.Entry<String, String> parameter
                : data.getParameters().entrySet()) {
            builder.append(parameter.getKey());
            if (!parameter.getValue().isEmpty()) {
                builder.append('=')
                        .append(parameter.getValue());
            }
            builder.append('\0');
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

}
