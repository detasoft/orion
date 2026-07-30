package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static pro.deta.orion.git.parser.wire.control.ControlState.MAX_PKT_LINE_LENGTH;
import static pro.deta.orion.git.parser.wire.control.ControlState.PKT_LINE_HEADER_SIZE;
import static pro.deta.orion.git.parser.wire.GitNativeUtils.hexDigit;

public final class GitNativeClientOutput {
    public static final int BUFFER_CAPACITY = 64 * 1024;

    private final ByteBuf output;
    private final Consumer<ByteBuf> sendToClient;
    private OutputSerialization serialization;
    private LegacySideBandResponse sideBandResponse;
    private ProtocolV2PackfileResponse protocolV2PackfileResponse;

    public GitNativeClientOutput(ByteBuf output) {
        this(
                output,
                ignored -> {
                    throw new IllegalStateException("not implemented");
                });
    }

    public GitNativeClientOutput(
            ByteBuf output,
            Consumer<ByteBuf> sendToClient) {
        this.output = Objects.requireNonNull(output, "output");
        this.sendToClient = Objects.requireNonNull(
                sendToClient,
                "sendToClient");
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
        return sendSerialization(
                new AsciiPacketSequenceSerialization(List.of(
                        "version 2\n",
                        "ls-refs=unborn\n",
                        "fetch\n",
                        "server-option\n")));
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

    public LegacySideBandResponse beginLegacySideBand64k(
            NativePackProducer producer,
            SideBandChannel channel) {
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(channel, "channel");
        if (serialization != null
                || sideBandResponse != null
                || protocolV2PackfileResponse != null) {
            producer.close();
            throw new IllegalStateException(
                    "Client output operation is already in progress");
        }
        LegacySideBandResponse response =
                new LegacySideBandResponse(producer, channel);
        sideBandResponse = response;
        return response;
    }

    public ProtocolV2PackfileResponse beginProtocolV2Packfile(
            NativePackProducer producer) {
        Objects.requireNonNull(producer, "producer");
        if (serialization != null
                || sideBandResponse != null
                || protocolV2PackfileResponse != null) {
            producer.close();
            throw new IllegalStateException(
                    "Client output operation is already in progress");
        }
        ProtocolV2PackfileResponse response =
                new ProtocolV2PackfileResponse(producer);
        protocolV2PackfileResponse = response;
        return response;
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
        for (int index = 0; index < payload.length(); index++) {
            if (payload.charAt(index) > 0x7f) {
                throw new IllegalArgumentException(
                        "Git pkt-line response must be ASCII");
            }
        }
        if (payload.length() + PKT_LINE_HEADER_SIZE
                > MAX_PKT_LINE_LENGTH) {
            throw new IllegalArgumentException(
                    "Git pkt-line exceeds maximum length");
        }
    }

    private SendResult sendSerialization(
            OutputSerialization operation) {
        if (serialization != null
                || sideBandResponse != null
                || protocolV2PackfileResponse != null) {
            return new SendResult.Failed(
                    "Client output operation is already in progress",
                    new IllegalStateException(
                            "Client output operation is already in progress"));
        }

        if (writeAvailable(operation)) {
            return new SendResult.Completed();
        }
        serialization = operation;
        SerializationTask task = new SerializationTask();
        return new SendResult.Streaming(task, task::failure);
    }

    private void finishStreaming() {
        OutputSerialization operation = serialization;
        if (operation == null) {
            throw new IllegalStateException(
                    "Client output operation is not in progress");
        }
        try {
            while (true) {
                submitOutput();
                if (writeAvailable(operation)) {
                    submitOutput();
                    return;
                }
            }
        } finally {
            serialization = null;
        }
    }

    private final class SerializationTask implements Runnable {
        private volatile SendResult.Failed failure;

        @Override
        public void run() {
            try {
                finishStreaming();
            } catch (Throwable cause) {
                failure = new SendResult.Failed(
                        "Failed to deliver serialized client output",
                        cause);
            }
        }

        private Optional<SendResult.Failed> failure() {
            return Optional.ofNullable(failure);
        }
    }

    private boolean writeAvailable(OutputSerialization operation) {
        return operation.writeAvailable(output);
    }

    private void submitOutput() {
        if (!output.isReadable()) {
            return;
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
            outputStartIndex = output.writerIndex();
        }

        public SendResult progress(ByteBuf message) {
            return enqueueMessage(
                    SideBandChannel.PROGRESS,
                    message);
        }

        public SendResult error(ByteBuf message) {
            return enqueueMessage(
                    SideBandChannel.ERROR,
                    message);
        }

        public SendResult advance() {
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
                    return new SendResult.Streaming(
                            this::submitSideBandOutput);
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

        private void submitSideBandOutput() {
            try {
                GitNativeClientOutput.this.submitOutput();
                outputStartIndex = output.writerIndex();
            } catch (Throwable failure) {
                deliveryFailure = failure;
                closeAfterFailure(failure);
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
                producer.close();
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

    public final class ProtocolV2PackfileResponse
            implements AutoCloseable {
        private static final byte[] PACKFILE_HEADER =
                {'0', '0', '0', 'd',
                        'p', 'a', 'c', 'k', 'f', 'i', 'l', 'e', '\n'};
        private static final byte[] FLUSH =
                {'0', '0', '0', '0'};
        private static final int MAXIMUM_PAYLOAD =
                MAX_PKT_LINE_LENGTH
                        - PKT_LINE_HEADER_SIZE
                        - 1;

        private final NativePackProducer producer;
        private Phase phase = Phase.HEADER;
        private int outputStartIndex;
        private int controlOffset;
        private Throwable deliveryFailure;
        private boolean closed;

        private ProtocolV2PackfileResponse(
                NativePackProducer producer) {
            this.producer = producer;
            outputStartIndex = output.writerIndex();
        }

        public SendResult advance() {
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
                        case HEADER -> writeControl(
                                PACKFILE_HEADER,
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
                    return new SendResult.Streaming(
                            this::submitPackfileOutput);
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

        private void submitPackfileOutput() {
            try {
                GitNativeClientOutput.this.submitOutput();
                outputStartIndex = output.writerIndex();
            } catch (Throwable failure) {
                deliveryFailure = failure;
                closeAfterFailure(failure);
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
                producer.close();
            } finally {
                if (protocolV2PackfileResponse == this) {
                    protocolV2PackfileResponse = null;
                }
            }
        }

        private enum Phase {
            HEADER,
            PACK,
            FLUSH,
            COMPLETED
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
}
