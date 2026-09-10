package pro.deta.orion.agentd.session;

import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionCommandSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.zip.CRC32C;

public final class NativeControlCodec {
    public static final int HEADER_LENGTH = 32;
    public static final int MAX_PAYLOAD_LENGTH = 16 * 1024 * 1024;
    private static final byte[] MAGIC = {'O', 'R', 'C', 'T'};
    private static final int VERSION = 1;
    private static final int OPERATION_PAYLOAD_SCHEMA = 3;
    private static final long STATUS_SEQUENCE = 1;

    public byte[] encode(ControlCommand command) {
        if (command instanceof ControlCommand.Input input) {
            byte[] bytes = input.bytes().toByteArray();
            ByteBuffer effect = payload(16 + bytes.length).order(ByteOrder.BIG_ENDIAN);
            effect.putLong(input.inputId().getMostSignificantBits());
            effect.putLong(input.inputId().getLeastSignificantBits());
            effect.put(bytes);
            return operationFrame(
                    1, input.sequence(), input.source(), input.serverCommandEnvelope(), effect.array());
        }
        if (command instanceof ControlCommand.Resize resize) {
            ByteBuffer effect = payload(8);
            effect.putInt(resize.columns()).putInt(resize.rows());
            return operationFrame(
                    2, resize.sequence(), resize.source(), resize.serverCommandEnvelope(), effect.array());
        }
        if (command instanceof ControlCommand.Signal signal) {
            ByteBuffer effect = payload(8);
            effect.putShort((short) signal.kind().wireCode()).putShort((short) 0);
            effect.putInt(signal.platformCode());
            return operationFrame(
                    3, signal.sequence(), signal.source(), signal.serverCommandEnvelope(), effect.array());
        }
        if (command instanceof ControlCommand.Terminate terminate) {
            ByteBuffer effect = payload(4);
            effect.putShort((short) terminate.mode().wireCode()).putShort((short) 0);
            return operationFrame(
                    4,
                    terminate.sequence(),
                    terminate.source(),
                    terminate.serverCommandEnvelope(),
                    effect.array());
        }
        if (command instanceof ControlCommand.AckJournal acknowledgement) {
            ByteBuffer effect = payload(8);
            effect.putLong(acknowledgement.acknowledgedEventId());
            return operationFrame(
                    7,
                    acknowledgement.sequence(),
                    acknowledgement.source(),
                    acknowledgement.serverCommandEnvelope(),
                    effect.array());
        }
        return frame(5, STATUS_SEQUENCE, new byte[0]);
    }

    public ControlResult decode(ControlCommand command, byte[] encodedFrame) {
        OptionalLong operationSequence = command.operationSequence();
        long expectedSequence = operationSequence.orElse(STATUS_SEQUENCE);
        String framingFailure = validateFrame(expectedSequence, encodedFrame);
        if (framingFailure != null) {
            return new ControlResult.Failed(
                    operationSequence, ControlResult.FailureKind.FRAMING, framingFailure);
        }
        ByteBuffer header = ByteBuffer.wrap(encodedFrame).order(ByteOrder.LITTLE_ENDIAN);
        int type = Short.toUnsignedInt(header.getShort(8));
        int payloadLength = header.getInt(24);
        ByteBuffer payload = ByteBuffer.wrap(encodedFrame, HEADER_LENGTH, payloadLength)
                .slice().order(ByteOrder.LITTLE_ENDIAN);
        try {
            return switch (type) {
                case 0x8000 -> received(command, payload);
                case 0x8002 -> rejection(operationSequence, payload);
                case 0x8003 -> status(command, payload);
                default -> failed(operationSequence, "unsupported response message type " + type);
            };
        } catch (IllegalArgumentException error) {
            return failed(operationSequence, error.getMessage());
        }
    }

    static byte[] frame(int type, long sequence, byte[] payload) {
        return frame(type, 1, sequence, payload);
    }

    static byte[] frame(int type, int payloadSchemaVersion, long sequence, byte[] payload) {
        if (payload.length > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("control payload exceeds 16 MiB");
        }
        ByteBuffer encoded = ByteBuffer.allocate(HEADER_LENGTH + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        encoded.put(MAGIC);
        encoded.putShort((short) VERSION);
        encoded.putShort((short) HEADER_LENGTH);
        encoded.putShort((short) type);
        encoded.putShort((short) payloadSchemaVersion);
        encoded.putInt(0);
        encoded.putLong(sequence);
        encoded.putInt(payload.length);
        encoded.putInt(checksum(payload));
        encoded.put(payload);
        return encoded.array();
    }

    private static byte[] operationPayload(
            SessionCommandSource source,
            Optional<ProtocolBytes> serverCommandEnvelope,
            byte[] effect
    ) {
        byte[] envelope = serverCommandEnvelope.map(ProtocolBytes::toByteArray).orElseGet(() -> new byte[0]);
        if (envelope.length > MAX_PAYLOAD_LENGTH - 2 * Short.BYTES - Integer.BYTES - effect.length) {
            throw new IllegalArgumentException("control payload exceeds 16 MiB");
        }
        ByteBuffer encoded = payload(2 * Short.BYTES + Integer.BYTES + envelope.length + effect.length);
        encoded.putShort((short) source.wireCode());
        encoded.putShort((short) 0);
        encoded.putInt(envelope.length);
        encoded.put(envelope);
        encoded.put(effect);
        return encoded.array();
    }

    private static byte[] operationFrame(
            int type,
            long sequence,
            SessionCommandSource source,
            Optional<ProtocolBytes> serverCommandEnvelope,
            byte[] effect
    ) {
        return frame(type, OPERATION_PAYLOAD_SCHEMA, sequence,
                operationPayload(source, serverCommandEnvelope, effect));
    }

    private static ByteBuffer payload(int length) {
        if (length > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("control payload exceeds 16 MiB");
        }
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static String validateFrame(long expectedSequence, byte[] frame) {
        if (frame == null || frame.length < HEADER_LENGTH) {
            return "response is shorter than the control header";
        }
        ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < MAGIC.length; index++) {
            if (frame[index] != MAGIC[index]) {
                return "response has bad magic";
            }
        }
        if (Short.toUnsignedInt(header.getShort(4)) != VERSION
                || Short.toUnsignedInt(header.getShort(6)) != HEADER_LENGTH
                || Short.toUnsignedInt(header.getShort(10)) != 1
                || header.getInt(12) != 0) {
            return "response has unsupported framing fields";
        }
        if (header.getLong(16) != expectedSequence) {
            return "response sequence does not match";
        }
        int payloadLength = header.getInt(24);
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_LENGTH
                || frame.length != HEADER_LENGTH + payloadLength) {
            return "response payload length is invalid";
        }
        byte[] payload = Arrays.copyOfRange(frame, HEADER_LENGTH, frame.length);
        if (checksum(payload) != header.getInt(28)) {
            return "response payload checksum does not match";
        }
        return null;
    }

    private static ControlResult received(ControlCommand command, ByteBuffer payload) {
        if (command.operationSequence().isEmpty()) {
            throw new IllegalArgumentException("RECEIVED response request is invalid");
        }
        if (payload.hasRemaining()) {
            return rejection(command.operationSequence(), payload);
        }
        return new ControlResult.Received(command.operationSequence().orElseThrow());
    }

    private static ControlResult rejection(OptionalLong operationSequence, ByteBuffer payload) {
        if (payload.remaining() < Integer.BYTES || payload.remaining() > Integer.BYTES + 4096) {
            throw new IllegalArgumentException("error payload length is invalid");
        }
        int code = payload.getInt();
        if (code < 1) {
            throw new IllegalArgumentException("error code is invalid");
        }
        byte[] detailBytes = new byte[payload.remaining()];
        payload.get(detailBytes);
        try {
            String detail = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(detailBytes)).toString();
            return new ControlResult.Rejected(operationSequence, code, detail);
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("error detail is not valid UTF-8");
        }
    }

    private static ControlResult status(ControlCommand command, ByteBuffer payload) {
        if (!(command instanceof ControlCommand.Status) || payload.remaining() != 64) {
            throw new IllegalArgumentException("STATUS response payload or request is invalid");
        }
        int stateCode = Short.toUnsignedInt(payload.getShort(0));
        HostStatus.State[] states = HostStatus.State.values();
        if (stateCode < 1 || stateCode > states.length) {
            throw new IllegalArgumentException("STATUS state is invalid");
        }
        int flags = Short.toUnsignedInt(payload.getShort(2));
        if ((flags & ~7) != 0) {
            throw new IllegalArgumentException("STATUS flags are invalid");
        }
        int columns = payload.getInt(4);
        int rows = payload.getInt(8);
        long hostPid = payload.getLong(12);
        long childPidValue = payload.getLong(20);
        if (columns < 1 || columns > 0xffff || rows < 1 || rows > 0xffff || hostPid <= 0
                || childPidValue == 0 || (childPidValue < 0 && childPidValue != -1)) {
            throw new IllegalArgumentException("STATUS process or terminal values are invalid");
        }
        int exitCode = payload.getInt(44);
        int exitSignal = payload.getInt(48);
        int journalVersion = Short.toUnsignedInt(payload.getShort(52));
        int controlVersion = Short.toUnsignedInt(payload.getShort(54));
        for (int index = 56; index < 64; index++) {
            if (payload.get(index) != 0) {
                throw new IllegalArgumentException("STATUS reserved bytes are nonzero");
            }
        }
        return new ControlResult.Status(new HostStatus(
                states[stateCode - 1],
                (flags & 1) != 0,
                (flags & 2) != 0,
                (flags & 4) != 0,
                columns,
                rows,
                hostPid,
                childPidValue == -1 ? OptionalLong.empty() : OptionalLong.of(childPidValue),
                exitCode == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(exitCode),
                exitSignal == -1 ? OptionalInt.empty() : OptionalInt.of(exitSignal),
                journalVersion,
                controlVersion));
    }

    private static ControlResult failed(OptionalLong operationSequence, String detail) {
        return new ControlResult.Failed(operationSequence, ControlResult.FailureKind.FRAMING, detail);
    }

    private static int checksum(byte[] payload) {
        CRC32C checksum = new CRC32C();
        checksum.update(payload, 0, payload.length);
        return (int) checksum.getValue();
    }
}
