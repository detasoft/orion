package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.continuation.ContinuationTask;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.upload.NativePackfileUri;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.output.DoubleGitOutputBufferCoordinator;
import pro.deta.orion.git.parser.wire.output.GitOutputBufferCoordinator;
import pro.deta.orion.util.Result;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CancellationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static pro.deta.orion.git.parser.wire.control.ControlState.MAX_PKT_LINE_LENGTH;
import static pro.deta.orion.git.parser.wire.control.ControlState.PKT_LINE_HEADER_SIZE;
import static pro.deta.orion.git.parser.wire.GitNativeUtils.hexDigit;

public final class GitNativeClientOutput {
    public static final int BUFFER_CAPACITY = 64 * 1024;

    private final GitOutputBufferCoordinator outputCoordinator;
    private ByteBuf output;
    private OutputSerialization serialization;
    private LegacySideBandResponse sideBandResponse;
    private LegacyPackResponse legacyPackResponse;
    private ProtocolV2PackfileResponse protocolV2PackfileResponse;

    public GitNativeClientOutput(ByteBuf output) {
        this(new DirectOutputBufferCoordinator(output));
    }

    public GitNativeClientOutput(
            ByteBuf output,
            Consumer<ByteBuf> sendToClient) {
        this(new CopyingOutputBufferCoordinator(output, sendToClient));
    }

    public GitNativeClientOutput(
            ByteBufAllocator allocator,
            GitNativeClientWrite write) {
        this(new DoubleGitOutputBufferCoordinator(
                Objects.requireNonNull(allocator, "allocator")
                        .buffer(BUFFER_CAPACITY, BUFFER_CAPACITY),
                allocator.buffer(BUFFER_CAPACITY, BUFFER_CAPACITY),
                write));
    }

    public GitNativeClientOutput(GitOutputBufferCoordinator outputCoordinator) {
        this.outputCoordinator = Objects.requireNonNull(
                outputCoordinator,
                "outputCoordinator");
        this.output = outputCoordinator.writableBuffer();
    }

    private static void requireFixedCapacity(ByteBuf output) {
        Objects.requireNonNull(output, "output");
        validateFixedCapacity(output);
    }

    private static void validateFixedCapacity(ByteBuf output) {
        if (output.capacity() != BUFFER_CAPACITY
                || output.maxCapacity() != BUFFER_CAPACITY) {
            throw new IllegalArgumentException(
                    "Native client output buffer must have a fixed 64 KiB capacity");
        }
    }

    public SendResult sendAdvertisement(
            GitV1Advertisement advertisement) {
        try {
            Objects.requireNonNull(advertisement, "advertisement");
            return sendSerialization(
                    new PacketListSerialization(
                            encodePackets(advertisement)));
        } catch (RuntimeException error) {
            return new SendResult.Failed(
                    "Failed to serialize Git advertisement",
                    error);
        }
    }

    public SendResult sendV2UploadPackAdvertisement() {
        return sendV2UploadPackAdvertisement(
                GitWireConfiguration.allSupported().protocolV2());
    }

    public SendResult sendV2UploadPackAdvertisement(
            GitWireConfiguration.ProtocolV2 configuration) {
        try {
            Objects.requireNonNull(configuration, "configuration");
            List<String> capabilities = new ArrayList<>();
            capabilities.add("version 2\n");
            if (configuration.lsRefs()) {
                capabilities.add(configuration.lsRefsUnborn()
                        ? "ls-refs=unborn\n"
                        : "ls-refs\n");
            }
            if (configuration.fetch()) {
                List<String> fetchOptions = new ArrayList<>();
                if (configuration.shallow()) {
                    fetchOptions.add("shallow");
                }
                if (configuration.waitForDone()) {
                    fetchOptions.add("wait-for-done");
                }
                if (configuration.filter()) {
                    fetchOptions.add("filter");
                }
                if (configuration.refInWant()) {
                    fetchOptions.add("ref-in-want");
                }
                if (configuration.sidebandAll()) {
                    fetchOptions.add("sideband-all");
                }
                if (configuration.packfileUris()) {
                    fetchOptions.add("packfile-uris");
                }
                capabilities.add(fetchOptions.isEmpty()
                        ? "fetch\n"
                        : "fetch="
                                + String.join(" ", fetchOptions)
                                + "\n");
            }
            if (configuration.serverOption()) {
                capabilities.add("server-option\n");
            }
            return sendSerialization(
                    new AsciiPacketSequenceSerialization(capabilities));
        } catch (RuntimeException error) {
            return new SendResult.Failed(
                    "Failed to serialize protocol v2 advertisement",
                    error);
        }
    }

    public SendResult sendLsRefs(GitLsRefsResponse response) {
        try {
            Objects.requireNonNull(response, "response");
            List<String> payloads = new ArrayList<>();
            for (GitLsRefsResponse.Ref ref : response.refs()) {
                Objects.requireNonNull(ref, "ref");
                String payload;
                if (ref instanceof GitLsRefsResponse.DirectRef direct) {
                    validateObjectId(direct.objectId());
                    validateToken(direct.name(), "direct.name");
                    Objects.requireNonNull(
                            direct.symrefTarget(),
                            "direct.symrefTarget");
                    Objects.requireNonNull(
                            direct.peeledObjectId(),
                            "direct.peeledObjectId");
                    if (direct.symrefTarget().isPresent()) {
                        validateToken(
                                direct.symrefTarget().get(),
                                "direct.symrefTarget");
                    }
                    if (direct.peeledObjectId().isPresent()) {
                        validateObjectId(
                                direct.peeledObjectId().get());
                    }
                    StringBuilder row = new StringBuilder()
                            .append(direct.objectId())
                            .append(' ')
                            .append(direct.name());
                    if (direct.symrefTarget().isPresent()) {
                        row.append(" symref-target:")
                                .append(direct.symrefTarget().get());
                    }
                    if (direct.peeledObjectId().isPresent()) {
                        row.append(" peeled:")
                                .append(direct.peeledObjectId().get());
                    }
                    payload = row.append('\n').toString();
                } else {
                    GitLsRefsResponse.UnbornRef unborn =
                            (GitLsRefsResponse.UnbornRef) ref;
                    validateToken(unborn.name(), "unborn.name");
                    validateToken(
                            unborn.symrefTarget(),
                            "unborn.symrefTarget");
                    payload = "unborn "
                            + unborn.name()
                            + " symref-target:"
                            + unborn.symrefTarget()
                            + "\n";
                }
                validateAsciiPacket(payload);
                payloads.add(payload);
            }
            return sendSerialization(
                    new AsciiPacketSequenceSerialization(payloads));
        } catch (RuntimeException error) {
            return new SendResult.Failed(
                    "Failed to serialize protocol v2 ls-refs response",
                    error);
        }
    }

    public SendResult sendError(String message) {
        try {
            Objects.requireNonNull(message, "message");
            if (message.isBlank()) {
                throw new IllegalArgumentException(
                        "message must not be blank");
            }
            String payload = "ERR " + message + "\n";
            validateAsciiPacket(payload);
            return sendSerialization(
                    new PktLineSerialization(
                            payload,
                            payload.length() + PKT_LINE_HEADER_SIZE));
        } catch (RuntimeException error) {
            return new SendResult.Failed(
                    "Failed to serialize Git error response",
                    error);
        }
    }

    public SendResult sendProtocolV2FetchAcknowledgments(
            List<GitObjectId> acknowledgments) {
        return sendProtocolV2FetchAcknowledgments(
                acknowledgments,
                false);
    }

    public SendResult sendProtocolV2FetchAcknowledgments(
            List<GitObjectId> acknowledgments,
            boolean sidebandAll) {
        try {
            Objects.requireNonNull(acknowledgments, "acknowledgments");
            List<String> payloads = new ArrayList<>();
            payloads.add("acknowledgments\n");
            if (acknowledgments.isEmpty()) {
                payloads.add("NAK\n");
            } else {
                for (GitObjectId acknowledgment : acknowledgments) {
                    Objects.requireNonNull(
                            acknowledgment,
                            "acknowledgment");
                    validateObjectId(acknowledgment.value());
                    payloads.add("ACK " + acknowledgment.value() + "\n");
                }
            }
            if (sidebandAll) {
                return sendSerialization(
                        new PacketListSerialization(
                                encodeAsciiPackets(payloads, true)));
            }
            return sendSerialization(
                    new AsciiPacketSequenceSerialization(payloads));
        } catch (RuntimeException error) {
            return new SendResult.Failed(
                    "Failed to serialize protocol v2 fetch acknowledgments",
                    error);
        }
    }

    public SendResult sendNak() {
        return sendPktLine(
                List.of("NAK\n"),
                "Failed to serialize legacy upload-pack NAK");
    }

    public SendResult sendAck(
            GitObjectId objectId,
            AckStatus status) {
        try {
            Objects.requireNonNull(objectId, "objectId");
            Objects.requireNonNull(status, "status");
            return sendPktLine(
                    List.of(
                            "ACK ",
                            objectId.value(),
                            status.wireSuffix,
                            "\n"),
                    "Failed to serialize legacy upload-pack ACK");
        } catch (RuntimeException error) {
            return new SendResult.Failed(
                    "Failed to serialize legacy upload-pack ACK",
                    error);
        }
    }

    public SendResult sendLegacyReceivePackStatus(
            List<ReceiveCommandStatus> statuses,
            boolean sideBand64k) {
        if (statuses == null) {
            return failedLegacyReceivePackStatus(
                    "statuses must not be null");
        }
        try {
            for (ReceiveCommandStatus status : statuses) {
                Optional<String> validationFailure =
                        receiveCommandStatusValidationFailure(status);
                if (validationFailure.isPresent()) {
                    return failedLegacyReceivePackStatus(
                            validationFailure.get());
                }
            }
            return sendSerialization(
                    new ReceivePackStatusSerialization(
                            List.copyOf(statuses),
                            sideBand64k));
        } catch (RuntimeException error) {
            return new SendResult.Failed(
                    "Failed to serialize legacy receive-pack status",
                    error);
        }
    }

    private static SendResult.Failed failedLegacyReceivePackStatus(
            String message) {
        return new SendResult.Failed(
                "Failed to serialize legacy receive-pack status",
                new IllegalArgumentException(message));
    }

    public LegacySideBandResponse beginLegacySideBand64k(
            NativePackProducer producer,
            SideBandChannel channel) {
        Objects.requireNonNull(channel, "channel");
        Result<NativePackProducer> availableProducer =
                availableProducer(producer);
        if (availableProducer instanceof
                Result.Failure<NativePackProducer> failure) {
            return new LegacySideBandResponse(
                    sendFailure(failure));
        }
        LegacySideBandResponse response =
                new LegacySideBandResponse(producer, channel);
        sideBandResponse = response;
        return response;
    }

    public LegacyPackResponse beginLegacyPack(
            NativePackProducer producer) {
        Result<NativePackProducer> availableProducer =
                availableProducer(producer);
        if (availableProducer instanceof
                Result.Failure<NativePackProducer> failure) {
            return new LegacyPackResponse(sendFailure(failure));
        }
        LegacyPackResponse response =
                new LegacyPackResponse(producer);
        legacyPackResponse = response;
        return response;
    }

    public ProtocolV2PackfileResponse beginProtocolV2Packfile(
            NativePackProducer producer) {
        return beginProtocolV2Packfile(producer, Set.of());
    }

    public ProtocolV2PackfileResponse beginProtocolV2Packfile(
            NativePackProducer producer,
            Set<GitObjectId> shallowBoundaries) {
        return beginProtocolV2Packfile(producer, shallowBoundaries, Map.of());
    }

    public ProtocolV2PackfileResponse beginProtocolV2Packfile(
            NativePackProducer producer,
            Set<GitObjectId> shallowBoundaries,
            Map<String, GitObjectId> wantedRefs) {
        return beginProtocolV2Packfile(
                producer,
                shallowBoundaries,
                wantedRefs,
                List.of(),
                false);
    }

    public ProtocolV2PackfileResponse beginProtocolV2Packfile(
            NativePackProducer producer,
            Set<GitObjectId> shallowBoundaries,
            Map<String, GitObjectId> wantedRefs,
            boolean sidebandAll) {
        return beginProtocolV2Packfile(
                producer,
                shallowBoundaries,
                wantedRefs,
                List.of(),
                sidebandAll);
    }

    public ProtocolV2PackfileResponse beginProtocolV2Packfile(
            NativePackProducer producer,
            Set<GitObjectId> shallowBoundaries,
            Map<String, GitObjectId> wantedRefs,
            List<NativePackfileUri> packfileUris) {
        return beginProtocolV2Packfile(
                producer,
                shallowBoundaries,
                wantedRefs,
                packfileUris,
                false);
    }

    public ProtocolV2PackfileResponse beginProtocolV2Packfile(
            NativePackProducer producer,
            Set<GitObjectId> shallowBoundaries,
            Map<String, GitObjectId> wantedRefs,
            List<NativePackfileUri> packfileUris,
            boolean sidebandAll) {
        Objects.requireNonNull(shallowBoundaries, "shallowBoundaries");
        Objects.requireNonNull(wantedRefs, "wantedRefs");
        Objects.requireNonNull(packfileUris, "packfileUris");
        Result<NativePackProducer> availableProducer =
                availableProducer(producer);
        if (availableProducer instanceof
                Result.Failure<NativePackProducer> failure) {
            return new ProtocolV2PackfileResponse(
                    sendFailure(failure));
        }
        ProtocolV2PackfileResponse response =
                new ProtocolV2PackfileResponse(
                        producer,
                        shallowBoundaries,
                        wantedRefs,
                        packfileUris,
                        sidebandAll);
        protocolV2PackfileResponse = response;
        return response;
    }

    private Result<NativePackProducer> availableProducer(
            NativePackProducer producer) {
        Objects.requireNonNull(producer, "producer");
        if (serialization != null
                || sideBandResponse != null
                || legacyPackResponse != null
                || protocolV2PackfileResponse != null) {
            producer.close();
            return new Result.Failure<>(
                    Result.FailureCode.GENERAL,
                    "Client output operation is already in progress",
                    new IllegalStateException(
                            "Client output operation is already in progress"));
        }
        return new Result.Success<>(producer);
    }

    private static SendResult.Failed sendFailure(
            Result.Failure<?> failure) {
        return new SendResult.Failed(
                failure.getMessage(),
                failure.throwable());
    }

    private SendResult sendPktLine(
            List<String> payloadParts,
            String failureMessage) {
        String payload = String.join("", payloadParts);
        for (int index = 0; index < payload.length(); index++) {
            if (payload.charAt(index) > 0x7f) {
                return new SendResult.Failed(
                        failureMessage,
                        new IllegalArgumentException(
                                "Git pkt-line response must be ASCII"));
            }
        }
        int packetLength = payload.length() + PKT_LINE_HEADER_SIZE;
        if (packetLength > MAX_PKT_LINE_LENGTH) {
            return new SendResult.Failed(
                    failureMessage,
                    new IllegalArgumentException(
                            "Git pkt-line exceeds maximum length"));
        }
        return sendSerialization(
                new PktLineSerialization(payload, packetLength));
    }

    private static void validateObjectId(String objectId) {
        Objects.requireNonNull(objectId, "objectId");
        if (objectId.length() != 40) {
            throw new IllegalArgumentException(
                    "Git object ID must contain 40 hexadecimal digits");
        }
        for (int index = 0; index < objectId.length(); index++) {
            char value = objectId.charAt(index);
            boolean hexadecimal = value >= '0' && value <= '9'
                    || value >= 'a' && value <= 'f'
                    || value >= 'A' && value <= 'F';
            if (!hexadecimal) {
                throw new IllegalArgumentException(
                        "Git object ID must contain 40 hexadecimal digits");
            }
        }
    }

    private static void validateToken(
            String token,
            String fieldName) {
        Objects.requireNonNull(token, fieldName);
        if (token.isEmpty()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be empty");
        }
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (value <= 0x20 || value >= 0x7f) {
                throw new IllegalArgumentException(
                        fieldName
                                + " must be a protocol-safe ASCII token");
            }
        }
    }

    private static void validateAsciiPacket(String payload) {
        validateAsciiPacket(payload, 0);
    }

    private static void validateAsciiPacket(
            String payload,
            int extraPayloadBytes) {
        for (int index = 0; index < payload.length(); index++) {
            if (payload.charAt(index) > 0x7f) {
                throw new IllegalArgumentException(
                        "Git pkt-line response must be ASCII");
            }
        }
        if (payload.length()
                + extraPayloadBytes
                + PKT_LINE_HEADER_SIZE
                > MAX_PKT_LINE_LENGTH) {
            throw new IllegalArgumentException(
                    "Git pkt-line exceeds maximum length");
        }
    }

    private static Optional<String> receiveCommandStatusValidationFailure(
            ReceiveCommandStatus status) {
        if (status == null) {
            return Optional.of("status must not be null");
        }
        Optional<String> refNameFailure = tokenValidationFailure(
                status.refName(),
                "status.refName");
        if (refNameFailure.isPresent()) {
            return refNameFailure;
        }
        Optional<String> messageFailure = status.ok()
                ? Optional.empty()
                : statusMessageValidationFailure(status.message());
        if (messageFailure.isPresent()) {
            return messageFailure;
        }
        int payloadLength = receiveCommandStatusPayload(status).length();
        if (payloadLength + PKT_LINE_HEADER_SIZE
                > MAX_PKT_LINE_LENGTH) {
            return Optional.of(
                    "Legacy receive-pack status exceeds maximum length");
        }
        return Optional.empty();
    }

    private static Optional<String> tokenValidationFailure(
            String token,
            String fieldName) {
        if (token == null) {
            return Optional.of(fieldName + " must not be null");
        }
        if (token.isEmpty()) {
            return Optional.of(fieldName + " must not be empty");
        }
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (value <= 0x20 || value >= 0x7f) {
                return Optional.of(
                        fieldName
                                + " must be a protocol-safe ASCII token");
            }
        }
        return Optional.empty();
    }

    private static Optional<String> statusMessageValidationFailure(
            String message) {
        if (message == null) {
            return Optional.of("status.message must not be null");
        }
        if (message.isBlank()) {
            return Optional.of("status.message must not be blank");
        }
        for (int index = 0; index < message.length(); index++) {
            char value = message.charAt(index);
            if (value <= 0x20 || value >= 0x7f) {
                return Optional.of(
                        "status.message must contain printable non-space ASCII");
            }
        }
        return Optional.empty();
    }

    private SendResult sendSerialization(
            OutputSerialization operation) {
        if (serialization != null
                || sideBandResponse != null
                || legacyPackResponse != null
                || protocolV2PackfileResponse != null) {
            return new SendResult.Failed(
                    "Client output operation is already in progress",
                    new IllegalStateException(
                            "Client output operation is already in progress"));
        }

        if (writeAvailable(operation)) {
            return completeOutput();
        }
        serialization = operation;
        SerializationTask task = new SerializationTask();
        return new SendResult.Streaming(task, task::failure);
    }

    private SendResult completeOutput() {
        try {
            return resultForCompletion(
                    outputCoordinator.finish(),
                    "Failed to deliver serialized client output");
        } catch (RuntimeException error) {
            return new SendResult.Failed(
                    "Failed to deliver serialized client output",
                    error);
        }
    }

    private CompletionStage<Void> finishStreaming() {
        OutputSerialization operation = serialization;
        if (operation == null) {
            return failedStage(new IllegalStateException(
                    "Client output operation is not in progress"));
        }
        CompletionStage<Void> completion = continueStreaming(operation);
        return completion.whenComplete((ignored, failure) ->
                serialization = null);
    }

    private CompletionStage<Void> continueStreaming(
            OutputSerialization operation) {
        try {
            CompletionStage<Void> writable = submitOutput();
            return writable.thenCompose(
                    ignored -> continueWhenWritable(operation));
        } catch (Throwable failure) {
            return failedStage(failure);
        }
    }

    private CompletionStage<Void> continueWhenWritable(
            OutputSerialization operation) {
        try {
            if (writeAvailable(operation)) {
                return outputCoordinator.finish();
            }
            return continueStreaming(operation);
        } catch (Throwable failure) {
            return failedStage(failure);
        }
    }

    private final class SerializationTask implements ContinuationTask {
        private volatile SendResult.Failed failure;
        private volatile CompletionStage<Void> completion =
                CompletableFuture.completedFuture(null);

        @Override
        public void run() {
            completion = finishStreaming()
                    .whenComplete((ignored, cause) -> {
                        if (cause != null) {
                            failure = new SendResult.Failed(
                                    "Failed to deliver serialized client output",
                                    unwrap(cause));
                        }
                    });
        }

        @Override
        public CompletionStage<Void> completion() {
            return completion;
        }

        private Optional<SendResult.Failed> failure() {
            return Optional.ofNullable(failure);
        }
    }

    private final class SubmitOutputTask implements ContinuationTask {
        private final String failureMessage;
        private final Consumer<Throwable> failureHandler;
        private final Runnable submittedHandler;
        private volatile SendResult.Failed failure;
        private volatile CompletionStage<Void> completion =
                CompletableFuture.completedFuture(null);

        private SubmitOutputTask(
                String failureMessage,
                Consumer<Throwable> failureHandler,
                Runnable submittedHandler) {
            this.failureMessage = Objects.requireNonNull(
                    failureMessage,
                    "failureMessage");
            this.failureHandler = Objects.requireNonNull(
                    failureHandler,
                    "failureHandler");
            this.submittedHandler = Objects.requireNonNull(
                    submittedHandler,
                    "submittedHandler");
        }

        @Override
        public void run() {
            try {
                CompletionStage<Void> submitted = submitOutput();
                completion = submitted
                        .thenRun(submittedHandler)
                        .whenComplete((ignored, cause) -> {
                            if (cause != null) {
                                recordFailure(unwrap(cause));
                            }
                        });
            } catch (Throwable cause) {
                recordFailure(cause);
                completion = failedStage(cause);
            }
        }

        @Override
        public CompletionStage<Void> completion() {
            return completion;
        }

        private Optional<SendResult.Failed> failure() {
            return Optional.ofNullable(failure);
        }

        private void recordFailure(Throwable cause) {
            if (failure == null) {
                failure = new SendResult.Failed(
                        failureMessage,
                        cause);
            }
            failureHandler.accept(cause);
        }
    }

    private SendResult resultForCompletion(
            CompletionStage<Void> completion,
            String failureMessage) {
        Optional<Throwable> completedFailure = completedFailure(completion);
        if (completedFailure.isPresent()) {
            return new SendResult.Failed(
                    failureMessage,
                    completedFailure.get());
        }
        if (completion.toCompletableFuture().isDone()) {
            return new SendResult.Completed();
        }
        AwaitCompletionTask task =
                new AwaitCompletionTask(completion, failureMessage);
        return new SendResult.Streaming(task, task::failure);
    }

    private static final class AwaitCompletionTask
            implements ContinuationTask {
        private final CompletionStage<Void> completion;
        private final String failureMessage;
        private volatile SendResult.Failed failure;

        private AwaitCompletionTask(
                CompletionStage<Void> completion,
                String failureMessage) {
            this.completion = Objects.requireNonNull(
                    completion,
                    "completion");
            this.failureMessage = Objects.requireNonNull(
                    failureMessage,
                    "failureMessage");
        }

        @Override
        public void run() {
            completion.whenComplete((ignored, cause) -> {
                if (cause != null) {
                    failure = new SendResult.Failed(
                            failureMessage,
                            unwrap(cause));
                }
            });
        }

        @Override
        public CompletionStage<Void> completion() {
            return completion;
        }

        private Optional<SendResult.Failed> failure() {
            return Optional.ofNullable(failure);
        }
    }

    private static Optional<Throwable> completedFailure(
            CompletionStage<Void> completion) {
        CompletableFuture<Void> future = completion.toCompletableFuture();
        if (!future.isDone()) {
            return Optional.empty();
        }
        try {
            future.getNow(null);
            return Optional.empty();
        } catch (CompletionException error) {
            return Optional.of(unwrap(error));
        } catch (CancellationException error) {
            return Optional.of(error);
        }
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException completionException
                && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return error;
    }

    private static CompletionStage<Void> failedStage(Throwable error) {
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(error);
        return failed;
    }

    private boolean writeAvailable(OutputSerialization operation) {
        return operation.writeAvailable(output);
    }

    private CompletionStage<Void> submitOutput() {
        if (!output.isReadable()) {
            return CompletableFuture.completedFuture(null);
        }
        outputCoordinator.submitReady();
        CompletionStage<Void> writable = outputCoordinator.awaitWritable();
        if (completedFailure(writable).isPresent()) {
            return writable;
        }
        if (writable.toCompletableFuture().isDone()) {
            refreshOutput();
            return CompletableFuture.completedFuture(null);
        }
        return writable.thenRun(this::refreshOutput);
    }

    private void refreshOutput() {
        output = outputCoordinator.writableBuffer();
    }

    private static List<byte[]> encodePackets(
            GitV1Advertisement advertisement) {
        List<byte[]> packets = new ArrayList<>();
        for (byte[] line : encodeLines(advertisement)) {
            int packetLength = line.length + PKT_LINE_HEADER_SIZE;
            if (packetLength > MAX_PKT_LINE_LENGTH) {
                throw new IllegalArgumentException(
                        "Advertisement line exceeds Git pkt-line limit");
            }
            byte[] packet = new byte[packetLength];
            writeHeader(packet, packetLength);
            System.arraycopy(
                    line,
                    0,
                    packet,
                    PKT_LINE_HEADER_SIZE,
                    line.length);
            packets.add(packet);
        }
        packets.add(new byte[] {'0', '0', '0', '0'});
        return List.copyOf(packets);
    }

    private static List<byte[]> encodeAsciiPackets(
            List<String> payloads,
            boolean sidebandAll) {
        List<byte[]> packets = new ArrayList<>();
        for (String payload : payloads) {
            packets.add(encodeAsciiPacket(payload, sidebandAll));
        }
        packets.add(new byte[] {'0', '0', '0', '0'});
        return List.copyOf(packets);
    }

    private static byte[] encodeAsciiPacket(String payload) {
        return encodeAsciiPacket(payload, false);
    }

    private static byte[] encodeAsciiPacket(
            String payload,
            boolean sidebandAll) {
        int sidebandLength = sidebandAll ? 1 : 0;
        validateAsciiPacket(payload, sidebandLength);
        int packetLength = payload.length()
                + PKT_LINE_HEADER_SIZE
                + sidebandLength;
        byte[] packet = new byte[packetLength];
        writeHeader(packet, packetLength);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.US_ASCII);
        int payloadOffset = PKT_LINE_HEADER_SIZE;
        if (sidebandAll) {
            packet[payloadOffset] = SideBandChannel.DATA.wireValue;
            payloadOffset++;
        }
        System.arraycopy(
                payloadBytes,
                0,
                packet,
                payloadOffset,
                payloadBytes.length);
        return packet;
    }

    private static List<byte[]> encodeLines(
            GitV1Advertisement advertisement) {
        List<byte[]> lines = new ArrayList<>();
        List<GitAdvertisedRef> refs = advertisement.refs();
        GitAdvertisedRef first = refs.getFirst();
        List<String> capabilityTokens = new ArrayList<>();
        for (GitCapability capability : advertisement.capabilities()) {
            capabilityTokens.add(capability.wireToken());
        }
        lines.add(encodeLine(
                first.objectId()
                        + " "
                        + first.name()
                        + "\0"
                        + String.join(" ", capabilityTokens)));
        addPeeled(lines, first);
        for (int index = 1; index < refs.size(); index++) {
            GitAdvertisedRef ref = refs.get(index);
            lines.add(encodeLine(ref.objectId() + " " + ref.name()));
            addPeeled(lines, ref);
        }
        return lines;
    }

    private static void addPeeled(
            List<byte[]> lines,
            GitAdvertisedRef ref) {
        ref.peeledObjectId().ifPresent(objectId -> lines.add(
                encodeLine(objectId + " " + ref.name() + "^{}")));
    }

    private static byte[] encodeLine(String value) {
        return (value + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void writeHeader(byte[] output, int packetLength) {
        output[0] = hexDigit((packetLength >>> 12) & 0x0f);
        output[1] = hexDigit((packetLength >>> 8) & 0x0f);
        output[2] = hexDigit((packetLength >>> 4) & 0x0f);
        output[3] = hexDigit(packetLength & 0x0f);
    }

    private static void writeHeader(
            ByteBuf output,
            int offset,
            int packetLength) {
        output.setByte(
                offset,
                hexDigit((packetLength >>> 12) & 0x0f));
        output.setByte(
                offset + 1,
                hexDigit((packetLength >>> 8) & 0x0f));
        output.setByte(
                offset + 2,
                hexDigit((packetLength >>> 4) & 0x0f));
        output.setByte(
                offset + 3,
                hexDigit(packetLength & 0x0f));
    }

    public void close() {
        outputCoordinator.close();
    }

    public sealed interface SendResult
            permits SendResult.Completed,
                    SendResult.Streaming,
                    SendResult.Failed {

        default <I> ContinuationFlow<I> transitionTo(
                Continuation<I> next) {
            Objects.requireNonNull(next, "next");
            return switch (this) {
                case Completed ignored ->
                        ContinuationFlow.transition(next);
                case Streaming streaming ->
                        ContinuationFlow.transitionAndYield(
                                new StreamingResumption<>(
                                        next,
                                        streaming.failure()),
                                streaming.task());
                case Failed failed ->
                        ContinuationFlow.completedError(
                                failed.message(),
                                failed.cause());
            };
        }

        record Completed() implements SendResult {
        }

        record Streaming(
                Runnable task,
                Supplier<Optional<Failed>> failure)
                implements SendResult {

            public Streaming(Runnable task) {
                this(task, Optional::empty);
            }

            public Streaming {
                Objects.requireNonNull(task, "task");
                Objects.requireNonNull(failure, "failure");
            }
        }

        record Failed(
                String message,
                Throwable cause) implements SendResult {
            public Failed {
                Objects.requireNonNull(message, "message");
                Objects.requireNonNull(cause, "cause");
            }
        }
    }

    private static final class StreamingResumption<I>
            implements Continuation<I> {
        private final Continuation<I> next;
        private final Supplier<Optional<SendResult.Failed>> failure;

        private StreamingResumption(
                Continuation<I> next,
                Supplier<Optional<SendResult.Failed>> failure) {
            this.next = Objects.requireNonNull(next, "next");
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        @Override
        public ContinuationFlow<I> process(I input) {
            Optional<SendResult.Failed> result =
                    Objects.requireNonNull(
                            failure.get(),
                            "failure outcome");
            if (result.isPresent()) {
                SendResult.Failed failed = result.get();
                return ContinuationFlow.completedError(
                        failed.message(),
                        failed.cause());
            }
            return ContinuationFlow.transition(next);
        }
    }

    public enum AckStatus {
        FINAL(""),
        CONTINUE(" continue"),
        COMMON(" common"),
        READY(" ready");

        private final String wireSuffix;

        AckStatus(String wireSuffix) {
            this.wireSuffix = wireSuffix;
        }
    }

    public enum SideBandChannel {
        DATA(1),
        PROGRESS(2),
        ERROR(3);

        private final byte wireValue;

        SideBandChannel(int wireValue) {
            this.wireValue = (byte) wireValue;
        }

        public byte wireValue() {
            return wireValue;
        }
    }

    public record ReceiveCommandStatus(
            String refName,
            boolean ok,
            String message) {
        public ReceiveCommandStatus {
            Objects.requireNonNull(refName, "refName");
            Objects.requireNonNull(message, "message");
        }
    }

    public final class LegacySideBandResponse
            implements AutoCloseable {
        private static final byte[] NAK =
                {'0', '0', '0', '8', 'N', 'A', 'K', '\n'};
        private static final byte[] FLUSH =
                {'0', '0', '0', '0'};
        private static final int MAXIMUM_PAYLOAD =
                MAX_PKT_LINE_LENGTH
                        - PKT_LINE_HEADER_SIZE
                        - 1;

        private final NativePackProducer producer;
        private final SideBandChannel channel;
        private final SendResult.Failed beginFailure;
        private final ArrayDeque<SideBandMessage> messages =
                new ArrayDeque<>();
        private Phase phase = Phase.NAK;
        private SideBandMessage currentMessage;
        private int outputStartIndex;
        private int controlOffset;
        private Throwable deliveryFailure;
        private boolean acceptingMessages = true;
        private boolean closed;

        private LegacySideBandResponse(
                NativePackProducer producer,
                SideBandChannel channel) {
            this.producer = producer;
            this.channel = channel;
            this.beginFailure = null;
            outputStartIndex = output.writerIndex();
        }

        private LegacySideBandResponse(
                SendResult.Failed beginFailure) {
            this.producer = null;
            this.channel = null;
            this.beginFailure = Objects.requireNonNull(
                    beginFailure,
                    "beginFailure");
            outputStartIndex = output.writerIndex();
        }

        public SendResult progress(ByteBuf message) {
            if (beginFailure != null) {
                return beginFailure;
            }
            return enqueueMessage(
                    SideBandChannel.PROGRESS,
                    message);
        }

        public SendResult error(ByteBuf message) {
            if (beginFailure != null) {
                return beginFailure;
            }
            return enqueueMessage(
                    SideBandChannel.ERROR,
                    message);
        }

        public SendResult advance() {
            if (beginFailure != null) {
                return beginFailure;
            }
            if (deliveryFailure != null) {
                return new SendResult.Failed(
                        "Failed to deliver legacy side-band-64k response",
                        deliveryFailure);
            }
            if (closed) {
                return new SendResult.Failed(
                        "Legacy side-band response is closed",
                        new IllegalStateException(
                                "Legacy side-band response is closed"));
            }
            try {
                writing:
                while (output.isWritable()
                        && phase != Phase.COMPLETED) {
                    switch (phase) {
                        case NAK -> writeControl(NAK, Phase.PACK);
                        case PACK -> {
                            if (!writeSideBandPacket()) {
                                break writing;
                            }
                            if (phase == Phase.DRAINING) {
                                break writing;
                            }
                        }
                        case DRAINING -> {
                            acceptingMessages = false;
                            if (currentMessage != null
                                    || !messages.isEmpty()) {
                                if (!writeMessagePacket()) {
                                    break writing;
                                }
                            } else {
                                phase = Phase.FLUSH;
                            }
                        }
                        case FLUSH -> writeControl(
                                FLUSH,
                                Phase.COMPLETED);
                        case COMPLETED -> {
                        }
                    }
                }
                if (output.isReadable()) {
                    SubmitOutputTask task = new SubmitOutputTask(
                            "Failed to deliver legacy side-band-64k response",
                            failure -> {
                                deliveryFailure = failure;
                                closeAfterFailure(failure);
                            },
                            () -> outputStartIndex = output.writerIndex());
                    return new SendResult.Streaming(task, task::failure);
                }
                complete();
                return new SendResult.Completed();
            } catch (RuntimeException error) {
                closeAfterFailure(error);
                return new SendResult.Failed(
                        "Failed to serialize legacy side-band-64k response",
                        error);
            }
        }

        private SendResult enqueueMessage(
                SideBandChannel messageChannel,
                ByteBuf message) {
            if (message == null) {
                return new SendResult.Failed(
                        "Failed to buffer legacy side-band message",
                        new NullPointerException("message"));
            }
            if (closed || !acceptingMessages) {
                return new SendResult.Failed(
                        "Legacy side-band response is not accepting messages",
                        new IllegalStateException(
                                "Legacy side-band response is not accepting messages"));
            }
            ByteBuf copy = null;
            try {
                copy = message.copy(
                        message.readerIndex(),
                        message.readableBytes());
                messages.addLast(new SideBandMessage(
                        messageChannel,
                        copy));
                copy = null;
                return new SendResult.Completed();
            } catch (RuntimeException error) {
                if (copy != null) {
                    try {
                        copy.release();
                    } catch (RuntimeException releaseFailure) {
                        error.addSuppressed(releaseFailure);
                    }
                }
                closeAfterFailure(error);
                return new SendResult.Failed(
                        "Failed to buffer legacy side-band message",
                        error);
            }
        }

        private void closeAfterFailure(Throwable failure) {
            try {
                close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }

        private void writeControl(
                byte[] control,
                Phase next) {
            int length = Math.min(
                    output.writableBytes(),
                    control.length - controlOffset);
            output.writeBytes(
                    control,
                    controlOffset,
                    length);
            controlOffset += length;
            if (controlOffset == control.length) {
                controlOffset = 0;
                phase = next;
            }
        }

        private boolean writeSideBandPacket() {
            if (currentMessage != null || !messages.isEmpty()) {
                return writeMessagePacket();
            }
            return writePackPacket();
        }

        private boolean writeMessagePacket() {
            if (currentMessage == null) {
                currentMessage = messages.removeFirst();
            }
            int packetCapacity = packetCapacity();
            if (packetCapacity < 0
                    || (packetCapacity == 0
                            && currentMessage.payload.isReadable())) {
                return false;
            }
            int payloadLength = Math.min(
                    packetCapacity,
                    currentMessage.payload.readableBytes());
            int packetOffset = output.writerIndex();
            output.writerIndex(
                    packetOffset
                            + PKT_LINE_HEADER_SIZE
                            + 1);
            output.setByte(
                    packetOffset + PKT_LINE_HEADER_SIZE,
                    currentMessage.channel.wireValue);
            output.writeBytes(
                    currentMessage.payload,
                    payloadLength);
            writeHeader(
                    output,
                    packetOffset,
                    PKT_LINE_HEADER_SIZE
                            + 1
                            + payloadLength);
            if (!currentMessage.payload.isReadable()) {
                currentMessage.payload.release();
                currentMessage = null;
            }
            return true;
        }

        private boolean writePackPacket() {
            int packetCapacity = packetCapacity();
            if (packetCapacity <= 0) {
                return false;
            }
            int packetOffset = output.writerIndex();
            output.writerIndex(
                    packetOffset
                            + PKT_LINE_HEADER_SIZE
                            + 1);
            output.setByte(
                    packetOffset + PKT_LINE_HEADER_SIZE,
                    channel.wireValue);
            ByteBuf payload = output.slice(
                    output.writerIndex(),
                    packetCapacity).clear();
            NativePackProducer.Result result =
                    producer.produce(payload);
            int payloadLength = payload.writerIndex();
            if (payloadLength == 0
                    && result == NativePackProducer.Result.MORE) {
                throw new IllegalStateException(
                        "Native pack producer made no progress");
            }
            output.writerIndex(
                    output.writerIndex() + payloadLength);
            writeHeader(
                    output,
                    packetOffset,
                    PKT_LINE_HEADER_SIZE
                            + 1
                            + payloadLength);
            if (result
                    == NativePackProducer.Result.COMPLETED) {
                phase = Phase.DRAINING;
            }
            return true;
        }

        private int packetCapacity() {
            int packetCapacity = Math.min(
                    MAXIMUM_PAYLOAD,
                    output.writableBytes()
                            - PKT_LINE_HEADER_SIZE
                            - 1);
            return packetCapacity >= 0
                    ? packetCapacity
                    : -1;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            acceptingMessages = false;
            rollbackOutput();
            try {
                if (producer != null) {
                    producer.close();
                }
            } finally {
                releaseMessages();
                if (sideBandResponse == this) {
                    sideBandResponse = null;
                }
            }
        }

        private void rollbackOutput() {
            if (output.writerIndex() >= outputStartIndex) {
                output.writerIndex(outputStartIndex);
            }
        }

        private void releaseMessages() {
            if (currentMessage != null) {
                currentMessage.payload.release();
                currentMessage = null;
            }
            SideBandMessage message;
            while ((message = messages.pollFirst()) != null) {
                message.payload.release();
            }
        }

        private void complete() {
            close();
        }

        private final class SideBandMessage {
            private final SideBandChannel channel;
            private final ByteBuf payload;

            private SideBandMessage(
                    SideBandChannel channel,
                    ByteBuf payload) {
                this.channel = channel;
                this.payload = payload;
            }
        }

        private enum Phase {
            NAK,
            PACK,
            DRAINING,
            FLUSH,
            COMPLETED
        }
    }

    public final class LegacyPackResponse
            implements AutoCloseable {
        private static final byte[] NAK =
                {'0', '0', '0', '8', 'N', 'A', 'K', '\n'};

        private final NativePackProducer producer;
        private final SendResult.Failed beginFailure;
        private Phase phase = Phase.NAK;
        private int outputStartIndex;
        private int controlOffset;
        private Throwable deliveryFailure;
        private boolean closed;

        private LegacyPackResponse(NativePackProducer producer) {
            this.producer = producer;
            this.beginFailure = null;
            outputStartIndex = output.writerIndex();
        }

        private LegacyPackResponse(SendResult.Failed beginFailure) {
            this.producer = null;
            this.beginFailure = Objects.requireNonNull(
                    beginFailure,
                    "beginFailure");
            outputStartIndex = output.writerIndex();
        }

        public SendResult advance() {
            if (beginFailure != null) {
                return beginFailure;
            }
            if (deliveryFailure != null) {
                return new SendResult.Failed(
                        "Failed to deliver legacy pack response",
                        deliveryFailure);
            }
            if (closed) {
                return new SendResult.Failed(
                        "Legacy pack response is closed",
                        new IllegalStateException(
                                "Legacy pack response is closed"));
            }
            try {
                writing:
                while (output.isWritable()
                        && phase != Phase.COMPLETED) {
                    switch (phase) {
                        case NAK -> writeControl(NAK, Phase.PACK);
                        case PACK -> {
                            if (!writePackBytes()) {
                                break writing;
                            }
                        }
                        case COMPLETED -> {
                        }
                    }
                }
                if (output.isReadable()) {
                    SubmitOutputTask task = new SubmitOutputTask(
                            "Failed to deliver legacy pack response",
                            failure -> {
                                deliveryFailure = failure;
                                closeAfterFailure(failure);
                            },
                            () -> outputStartIndex = output.writerIndex());
                    return new SendResult.Streaming(task, task::failure);
                }
                close();
                return new SendResult.Completed();
            } catch (RuntimeException error) {
                closeAfterFailure(error);
                return new SendResult.Failed(
                        "Failed to serialize legacy pack response",
                        error);
            }
        }

        private void writeControl(
                byte[] control,
                Phase next) {
            int length = Math.min(
                    output.writableBytes(),
                    control.length - controlOffset);
            output.writeBytes(
                    control,
                    controlOffset,
                    length);
            controlOffset += length;
            if (controlOffset == control.length) {
                controlOffset = 0;
                phase = next;
            }
        }

        private boolean writePackBytes() {
            int packetCapacity = output.writableBytes();
            if (packetCapacity <= 0) {
                return false;
            }
            ByteBuf payload = output.slice(
                    output.writerIndex(),
                    packetCapacity).clear();
            NativePackProducer.Result result =
                    producer.produce(payload);
            int payloadLength = payload.writerIndex();
            if (payloadLength == 0
                    && result == NativePackProducer.Result.MORE) {
                throw new IllegalStateException(
                        "Native pack producer made no progress");
            }
            output.writerIndex(
                    output.writerIndex() + payloadLength);
            if (result == NativePackProducer.Result.COMPLETED) {
                phase = Phase.COMPLETED;
            }
            return true;
        }

        private void closeAfterFailure(Throwable failure) {
            try {
                close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (output.writerIndex() >= outputStartIndex) {
                output.writerIndex(outputStartIndex);
            }
            try {
                if (producer != null) {
                    producer.close();
                }
            } finally {
                if (legacyPackResponse == this) {
                    legacyPackResponse = null;
                }
            }
        }

        private enum Phase {
            NAK,
            PACK,
            COMPLETED
        }
    }

    public final class ProtocolV2PackfileResponse
            implements AutoCloseable {
        private static final byte[] PACKFILE_HEADER =
                {'0', '0', '0', 'd',
                        'p', 'a', 'c', 'k', 'f', 'i', 'l', 'e', '\n'};
        private static final byte[] DELIMITER =
                {'0', '0', '0', '1'};
        private static final byte[] FLUSH =
                {'0', '0', '0', '0'};
        private static final int MAXIMUM_PAYLOAD =
                MAX_PKT_LINE_LENGTH
                        - PKT_LINE_HEADER_SIZE
                        - 1;

        private final NativePackProducer producer;
        private final List<byte[]> prePackSectionPackets;
        private final SendResult.Failed beginFailure;
        private final byte[] packfileHeader;
        private Phase phase;
        private int outputStartIndex;
        private int controlOffset;
        private int prePackSectionPacketIndex;
        private Throwable deliveryFailure;
        private boolean closed;

        private ProtocolV2PackfileResponse(
                NativePackProducer producer,
                Set<GitObjectId> shallowBoundaries,
                Map<String, GitObjectId> wantedRefs,
                List<NativePackfileUri> packfileUris,
                boolean sidebandAll) {
            this.producer = producer;
            this.prePackSectionPackets = prePackSectionPackets(
                    shallowBoundaries,
                    wantedRefs,
                    packfileUris,
                    sidebandAll);
            this.beginFailure = null;
            this.packfileHeader = sidebandAll
                    ? encodeAsciiPacket("packfile\n", true)
                    : PACKFILE_HEADER;
            this.phase = prePackSectionPackets.isEmpty()
                    ? Phase.HEADER
                    : Phase.PRE_PACK_SECTIONS;
            outputStartIndex = output.writerIndex();
        }

        private ProtocolV2PackfileResponse(
                SendResult.Failed beginFailure) {
            this.producer = null;
            this.prePackSectionPackets = List.of();
            this.beginFailure = Objects.requireNonNull(
                    beginFailure,
                    "beginFailure");
            this.packfileHeader = PACKFILE_HEADER;
            this.phase = Phase.HEADER;
            outputStartIndex = output.writerIndex();
        }

        public SendResult advance() {
            if (beginFailure != null) {
                return beginFailure;
            }
            if (deliveryFailure != null) {
                return new SendResult.Failed(
                        "Failed to deliver protocol v2 packfile response",
                        deliveryFailure);
            }
            if (closed) {
                return new SendResult.Failed(
                        "Protocol v2 packfile response is closed",
                        new IllegalStateException(
                                "Protocol v2 packfile response is closed"));
            }
            try {
                writing:
                while (output.isWritable()
                        && phase != Phase.COMPLETED) {
                    switch (phase) {
                        case PRE_PACK_SECTIONS -> {
                            if (!writePrePackSections()) {
                                break writing;
                            }
                        }
                        case HEADER -> writeControl(
                                packfileHeader,
                                Phase.PACK);
                        case PACK -> {
                            if (!writePackPacket()) {
                                break writing;
                            }
                        }
                        case FLUSH -> writeControl(
                                FLUSH,
                                Phase.COMPLETED);
                        case COMPLETED -> {
                        }
                    }
                }
                if (output.isReadable()) {
                    SubmitOutputTask task = new SubmitOutputTask(
                            "Failed to deliver protocol v2 packfile response",
                            failure -> {
                                deliveryFailure = failure;
                                closeAfterFailure(failure);
                            },
                            () -> outputStartIndex = output.writerIndex());
                    return new SendResult.Streaming(task, task::failure);
                }
                close();
                return new SendResult.Completed();
            } catch (RuntimeException error) {
                closeAfterFailure(error);
                return new SendResult.Failed(
                        "Failed to serialize protocol v2 packfile response",
                        error);
            }
        }

        private void writeControl(
                byte[] control,
                Phase next) {
            int length = Math.min(
                    output.writableBytes(),
                    control.length - controlOffset);
            output.writeBytes(
                    control,
                    controlOffset,
                    length);
            controlOffset += length;
            if (controlOffset == control.length) {
                controlOffset = 0;
                phase = next;
            }
        }

        private boolean writePrePackSections() {
            while (prePackSectionPacketIndex < prePackSectionPackets.size()) {
                byte[] packet =
                        prePackSectionPackets.get(
                                prePackSectionPacketIndex);
                int length = Math.min(
                        output.writableBytes(),
                        packet.length - controlOffset);
                output.writeBytes(packet, controlOffset, length);
                controlOffset += length;
                if (controlOffset < packet.length) {
                    return false;
                }
                prePackSectionPacketIndex++;
                controlOffset = 0;
            }
            phase = Phase.HEADER;
            return true;
        }

        private boolean writePackPacket() {
            int packetCapacity = Math.min(
                    MAXIMUM_PAYLOAD,
                    output.writableBytes()
                            - PKT_LINE_HEADER_SIZE
                            - 1);
            if (packetCapacity <= 0) {
                return false;
            }
            int packetOffset = output.writerIndex();
            output.writerIndex(
                    packetOffset
                            + PKT_LINE_HEADER_SIZE
                            + 1);
            output.setByte(
                    packetOffset + PKT_LINE_HEADER_SIZE,
                    SideBandChannel.DATA.wireValue);
            ByteBuf payload = output.slice(
                    output.writerIndex(),
                    packetCapacity).clear();
            NativePackProducer.Result result =
                    producer.produce(payload);
            int payloadLength = payload.writerIndex();
            if (payloadLength == 0
                    && result == NativePackProducer.Result.MORE) {
                throw new IllegalStateException(
                        "Native pack producer made no progress");
            }
            output.writerIndex(
                    output.writerIndex() + payloadLength);
            writeHeader(
                    output,
                    packetOffset,
                    PKT_LINE_HEADER_SIZE
                            + 1
                            + payloadLength);
            if (result == NativePackProducer.Result.COMPLETED) {
                phase = Phase.FLUSH;
            }
            return true;
        }

        private void closeAfterFailure(Throwable failure) {
            try {
                close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (output.writerIndex() >= outputStartIndex) {
                output.writerIndex(outputStartIndex);
            }
            try {
                if (producer != null) {
                    producer.close();
                }
            } finally {
                if (protocolV2PackfileResponse == this) {
                    protocolV2PackfileResponse = null;
                }
            }
        }

        private enum Phase {
            PRE_PACK_SECTIONS,
            HEADER,
            PACK,
            FLUSH,
            COMPLETED
        }

        private static List<byte[]> prePackSectionPackets(
                Set<GitObjectId> shallowBoundaries,
                Map<String, GitObjectId> wantedRefs,
                List<NativePackfileUri> packfileUris,
                boolean sidebandAll) {
            Objects.requireNonNull(
                    shallowBoundaries,
                    "shallowBoundaries");
            Objects.requireNonNull(wantedRefs, "wantedRefs");
            Objects.requireNonNull(packfileUris, "packfileUris");
            if (shallowBoundaries.isEmpty()
                    && wantedRefs.isEmpty()
                    && packfileUris.isEmpty()) {
                return List.of();
            }
            List<byte[]> packets = new ArrayList<>();
            if (!shallowBoundaries.isEmpty()) {
                packets.add(encodeAsciiPacket(
                        "shallow-info\n",
                        sidebandAll));
                for (GitObjectId shallowBoundary : shallowBoundaries) {
                    Objects.requireNonNull(
                            shallowBoundary,
                            "shallowBoundary");
                    validateObjectId(shallowBoundary.value());
                    packets.add(encodeAsciiPacket(
                            "shallow "
                                    + shallowBoundary.value()
                                    + "\n",
                            sidebandAll));
                }
                packets.add(DELIMITER);
            }
            if (!wantedRefs.isEmpty()) {
                packets.add(encodeAsciiPacket(
                        "wanted-refs\n",
                        sidebandAll));
                for (Map.Entry<String, GitObjectId> wantedRef
                        : new LinkedHashMap<>(wantedRefs).entrySet()) {
                    String refName = validateRefName(
                            wantedRef.getKey(),
                            "wantedRef.name");
                    GitObjectId objectId = Objects.requireNonNull(
                            wantedRef.getValue(),
                            "wantedRef.objectId");
                    validateObjectId(objectId.value());
                    packets.add(encodeAsciiPacket(
                            objectId.value()
                                    + " "
                                    + refName
                                    + "\n",
                            sidebandAll));
                }
                packets.add(DELIMITER);
            }
            if (!packfileUris.isEmpty()) {
                packets.add(encodeAsciiPacket(
                        "packfile-uris\n",
                        sidebandAll));
                for (NativePackfileUri packfileUri : packfileUris) {
                    Objects.requireNonNull(packfileUri, "packfileUri");
                    packets.add(encodeAsciiPacket(
                            packfileUri.packHash()
                                    + " "
                                    + packfileUri.uri()
                                    + "\n",
                            sidebandAll));
                }
                packets.add(DELIMITER);
            }
            return List.copyOf(packets);
        }

        private static String validateRefName(
                String refName,
                String fieldName) {
            Objects.requireNonNull(refName, fieldName);
            if (!isValidWantedRefName(refName)) {
                throw new IllegalArgumentException(
                        fieldName + " must be HEAD or a full Git ref name");
            }
            return refName;
        }

        private static boolean isValidWantedRefName(String refName) {
            return "HEAD".equals(refName) || isValidFullRefName(refName);
        }

        private static boolean isValidFullRefName(String refName) {
            if (!refName.startsWith("refs/")
                    || refName.length() == "refs/".length()
                    || refName.endsWith("/")
                    || refName.contains("//")
                    || refName.contains("..")
                    || refName.contains("@{")) {
                return false;
            }
            for (int index = 0; index < refName.length(); index++) {
                char value = refName.charAt(index);
                if (value <= 0x20
                        || value >= 0x7f
                        || value == '~'
                        || value == '^'
                        || value == ':'
                        || value == '?'
                        || value == '*'
                        || value == '['
                        || value == '\\') {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class DirectOutputBufferCoordinator
            implements GitOutputBufferCoordinator {
        private final ByteBuf output;

        private DirectOutputBufferCoordinator(ByteBuf output) {
            this.output = Objects.requireNonNull(output, "output");
            requireFixedCapacity(output);
        }

        @Override
        public ByteBuf writableBuffer() {
            return output;
        }

        @Override
        public CompletionStage<Void> submitReady() {
            if (output.isReadable()) {
                throw new IllegalStateException("not implemented");
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> awaitWritable() {
            return output.isWritable()
                    ? CompletableFuture.completedFuture(null)
                    : failedStage(new IllegalStateException(
                            "not implemented"));
        }

        @Override
        public CompletionStage<Void> finish() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
        }
    }

    private static final class CopyingOutputBufferCoordinator
            implements GitOutputBufferCoordinator {
        private final ByteBuf output;
        private final Consumer<ByteBuf> sendToClient;

        private CopyingOutputBufferCoordinator(
                ByteBuf output,
                Consumer<ByteBuf> sendToClient) {
            this.output = Objects.requireNonNull(output, "output");
            this.sendToClient = Objects.requireNonNull(
                    sendToClient,
                    "sendToClient");
            requireFixedCapacity(output);
        }

        @Override
        public ByteBuf writableBuffer() {
            return output;
        }

        @Override
        public CompletionStage<Void> submitReady() {
            if (!output.isReadable()) {
                return CompletableFuture.completedFuture(null);
            }
            ByteBuf submitted = output.copy(
                    output.readerIndex(),
                    output.readableBytes());
            try {
                sendToClient.accept(submitted);
            } catch (Throwable failure) {
                submitted.release();
                throw failure;
            } finally {
                output.clear();
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> awaitWritable() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> finish() {
            return submitReady();
        }

        @Override
        public void close() {
        }
    }

    private interface OutputSerialization {
        boolean writeAvailable(ByteBuf output);
    }

    private static final class PacketListSerialization
            implements OutputSerialization {
        private final List<byte[]> packets;
        private int packetIndex;
        private int packetOffset;

        private PacketListSerialization(List<byte[]> packets) {
            this.packets = packets;
        }

        @Override
        public boolean writeAvailable(ByteBuf output) {
            while (packetIndex < packets.size()) {
                byte[] packet = packets.get(packetIndex);
                int remaining = packet.length - packetOffset;
                int writable = Math.min(
                        output.writableBytes(),
                        remaining);
                output.writeBytes(packet, packetOffset, writable);
                packetOffset += writable;
                if (packetOffset == packet.length) {
                    packetIndex++;
                    packetOffset = 0;
                }
                if (!output.isWritable()) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class PktLineSerialization
            implements OutputSerialization {
        private final String payload;
        private final int packetLength;
        private int packetOffset;

        private PktLineSerialization(
                String payload,
                int packetLength) {
            this.payload = payload;
            this.packetLength = packetLength;
        }

        @Override
        public boolean writeAvailable(ByteBuf output) {
            while (packetOffset < packetLength
                    && output.isWritable()) {
                output.writeByte(byteAt(packetOffset));
                packetOffset++;
            }
            return packetOffset == packetLength;
        }

        private byte byteAt(int offset) {
            if (offset < PKT_LINE_HEADER_SIZE) {
                int shift = (PKT_LINE_HEADER_SIZE - 1 - offset) * 4;
                return hexDigit((packetLength >>> shift) & 0x0f);
            }
            return (byte) payload.charAt(
                    offset - PKT_LINE_HEADER_SIZE);
        }
    }

    private static final class AsciiPacketSequenceSerialization
            implements OutputSerialization {
        private final List<String> payloads;
        private int packetIndex;
        private int packetOffset;

        private AsciiPacketSequenceSerialization(
                List<String> payloads) {
            this.payloads = List.copyOf(payloads);
        }

        @Override
        public boolean writeAvailable(ByteBuf output) {
            while (packetIndex <= payloads.size()
                    && output.isWritable()) {
                String payload = packetIndex < payloads.size()
                        ? payloads.get(packetIndex)
                        : "";
                int packetLength = packetIndex < payloads.size()
                        ? payload.length() + PKT_LINE_HEADER_SIZE
                        : 0;
                while (packetOffset
                        < packetSize(payload, packetLength)
                        && output.isWritable()) {
                    output.writeByte(byteAt(
                            payload,
                            packetLength,
                            packetOffset));
                    packetOffset++;
                }
                if (packetOffset
                        == packetSize(payload, packetLength)) {
                    packetIndex++;
                    packetOffset = 0;
                }
            }
            return packetIndex > payloads.size();
        }

        private static int packetSize(
                String payload,
                int packetLength) {
            return packetLength == 0
                    ? PKT_LINE_HEADER_SIZE
                    : payload.length() + PKT_LINE_HEADER_SIZE;
        }

        private static byte byteAt(
                String payload,
                int packetLength,
                int offset) {
            if (offset < PKT_LINE_HEADER_SIZE) {
                int shift =
                        (PKT_LINE_HEADER_SIZE - 1 - offset) * 4;
                return hexDigit((packetLength >>> shift) & 0x0f);
            }
            return (byte) payload.charAt(
                    offset - PKT_LINE_HEADER_SIZE);
        }

    }

    private static final class ReceivePackStatusSerialization
            implements OutputSerialization {
        private static final String UNPACK_OK = "unpack ok\n";

        private final List<ReceiveCommandStatus> statuses;
        private final boolean sideBand64k;
        private int packetIndex;
        private int packetOffset;

        private ReceivePackStatusSerialization(
                List<ReceiveCommandStatus> statuses,
                boolean sideBand64k) {
            this.statuses = statuses;
            this.sideBand64k = sideBand64k;
        }

        @Override
        public boolean writeAvailable(ByteBuf output) {
            while (packetIndex < packetCount()
                    && output.isWritable()) {
                int packetSize = packetSize();
                while (packetOffset < packetSize
                        && output.isWritable()) {
                    output.writeByte(byteAt(packetOffset));
                    packetOffset++;
                }
                if (packetOffset == packetSize) {
                    packetIndex++;
                    packetOffset = 0;
                }
            }
            return packetIndex == packetCount();
        }

        private int packetCount() {
            int innerPacketCount = statuses.size() + 2;
            return sideBand64k
                    ? innerPacketCount + 1
                    : innerPacketCount;
        }

        private int packetSize() {
            return packetLength() == 0
                    ? PKT_LINE_HEADER_SIZE
                    : packetLength();
        }

        private int packetLength() {
            if (outerFlush()) {
                return 0;
            }
            if (!sideBand64k) {
                return innerPacketLength();
            }
            return PKT_LINE_HEADER_SIZE
                    + 1
                    + innerPacketSize();
        }

        private boolean outerFlush() {
            return sideBand64k
                    && packetIndex == statuses.size() + 2;
        }

        private int innerPacketSize() {
            return innerPacketLength() == 0
                    ? PKT_LINE_HEADER_SIZE
                    : innerPacketLength();
        }

        private int innerPacketLength() {
            String payload = innerPayload();
            return payload == null
                    ? 0
                    : payload.length() + PKT_LINE_HEADER_SIZE;
        }

        private String innerPayload() {
            if (packetIndex == 0) {
                return UNPACK_OK;
            }
            int statusIndex = packetIndex - 1;
            if (statusIndex < statuses.size()) {
                return receiveCommandStatusPayload(
                        statuses.get(statusIndex));
            }
            return null;
        }

        private byte byteAt(int offset) {
            if (packetLength() == 0) {
                return '0';
            }
            if (!sideBand64k) {
                return innerByteAt(offset);
            }
            if (offset < PKT_LINE_HEADER_SIZE) {
                return headerByte(packetLength(), offset);
            }
            if (offset == PKT_LINE_HEADER_SIZE) {
                return SideBandChannel.DATA.wireValue();
            }
            return innerByteAt(offset - PKT_LINE_HEADER_SIZE - 1);
        }

        private byte innerByteAt(int offset) {
            int innerPacketLength = innerPacketLength();
            if (innerPacketLength == 0) {
                return '0';
            }
            if (offset < PKT_LINE_HEADER_SIZE) {
                return headerByte(innerPacketLength, offset);
            }
            return (byte) innerPayload().charAt(
                    offset - PKT_LINE_HEADER_SIZE);
        }

        private static byte headerByte(
                int packetLength,
                int offset) {
            int shift = (PKT_LINE_HEADER_SIZE - 1 - offset) * 4;
            return hexDigit((packetLength >>> shift) & 0x0f);
        }
    }

    private static String receiveCommandStatusPayload(
            ReceiveCommandStatus status) {
        return status.ok()
                ? "ok " + status.refName() + "\n"
                : "ng "
                        + status.refName()
                        + " "
                        + status.message()
                        + "\n";
    }
}
