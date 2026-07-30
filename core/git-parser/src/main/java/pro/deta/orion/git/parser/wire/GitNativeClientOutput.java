package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static pro.deta.orion.git.parser.wire.control.ControlState.MAX_PKT_LINE_LENGTH;
import static pro.deta.orion.git.parser.wire.control.ControlState.PKT_LINE_HEADER_SIZE;
import static pro.deta.orion.git.parser.wire.GitNativeUtils.hexDigit;

public final class GitNativeClientOutput {
    public static final int BUFFER_CAPACITY = 64 * 1024;

    private final ByteBuf output;
    private final Consumer<ByteBuf> sendToClient;
    private OutputSerialization serialization;
    private LegacySideBandResponse sideBandResponse;

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
                        "ls-refs\n",
                        "fetch=shallow\n",
                        "server-option\n")));
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
        if (serialization != null || sideBandResponse != null) {
            producer.close();
            throw new IllegalStateException(
                    "Client output operation is already in progress");
        }
        LegacySideBandResponse response =
                new LegacySideBandResponse(producer, channel);
        sideBandResponse = response;
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

    private SendResult sendSerialization(
            OutputSerialization operation) {
        if (serialization != null) {
            return new SendResult.Failed(
                    "Client output operation is already in progress",
                    new IllegalStateException(
                            "Client output operation is already in progress"));
        }

        if (writeAvailable(operation)) {
            return new SendResult.Completed();
        }
        serialization = operation;
        return new SendResult.Streaming(this::finishStreaming);
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
                                next,
                                streaming.task());
                case Failed failed ->
                        ContinuationFlow.completedError(
                                failed.message(),
                                failed.cause());
            };
        }

        record Completed() implements SendResult {
        }

        record Streaming(Runnable task) implements SendResult {
            public Streaming {
                Objects.requireNonNull(task, "task");
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
        private Phase phase = Phase.NAK;
        private int controlOffset;
        private boolean closed;

        private LegacySideBandResponse(
                NativePackProducer producer,
                SideBandChannel channel) {
            this.producer = producer;
            this.channel = channel;
        }

        public SendResult advance() {
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
                            GitNativeClientOutput.this::submitOutput);
                }
                complete();
                return new SendResult.Completed();
            } catch (RuntimeException error) {
                close();
                return new SendResult.Failed(
                        "Failed to serialize legacy side-band-64k response",
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
                phase = Phase.FLUSH;
            }
            return true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            producer.close();
            if (sideBandResponse == this) {
                sideBandResponse = null;
            }
        }

        private void complete() {
            close();
        }

        private enum Phase {
            NAK,
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
