package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
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
    private Serialization serialization;

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
        Objects.requireNonNull(advertisement, "advertisement");
        if (serialization != null) {
            throw new IllegalStateException(
                    "Client output operation is already in progress");
        }

        Serialization operation = new Serialization(
                advertisement,
                encodePackets(advertisement));
        if (writeAvailable(operation)) {
            return new SendResult.Completed();
        }
        serialization = operation;
        return new SendResult.Streaming(this::finishStreaming);
    }

    private void finishStreaming() {
        Serialization operation = serialization;
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

    private boolean writeAvailable(Serialization operation) {
        while (operation.packetIndex < operation.packets.size()) {
            byte[] packet = operation.packets.get(operation.packetIndex);
            int remaining = packet.length - operation.packetOffset;
            int writable = Math.min(output.writableBytes(), remaining);
            output.writeBytes(packet, operation.packetOffset, writable);
            operation.packetOffset += writable;
            if (operation.packetOffset == packet.length) {
                operation.packetIndex++;
                operation.packetOffset = 0;
            }
            if (!output.isWritable()) {
                return false;
            }
        }
        return true;
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

    public sealed interface SendResult
            permits SendResult.Completed, SendResult.Streaming {

        record Completed() implements SendResult {
        }

        record Streaming(Runnable task) implements SendResult {
            public Streaming {
                Objects.requireNonNull(task, "task");
            }
        }
    }

    private static final class Serialization {
        private final GitV1Advertisement advertisement;
        private final List<byte[]> packets;
        private int packetIndex;
        private int packetOffset;

        private Serialization(
                GitV1Advertisement advertisement,
                List<byte[]> packets) {
            this.advertisement = advertisement;
            this.packets = packets;
        }
    }
}
