package pro.deta.orion.agentd.session;

import pro.deta.orion.agent.protocol.CommandId;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.zip.CRC32C;

public final class NativeControlCodec {
    public static final int HEADER_LENGTH = 32;
    public static final int MAX_PAYLOAD_LENGTH = 16 * 1024 * 1024;
    private static final byte[] MAGIC = {'O', 'R', 'C', 'T'};
    private static final int VERSION = 1;

    public byte[] encode(ControlCommand command, long requestId) {
        if (command instanceof ControlCommand.Input input) {
            byte[] bytes = input.bytes().toByteArray();
            ByteBuffer payload = payload(16 + bytes.length).order(ByteOrder.BIG_ENDIAN);
            payload.putLong(input.inputId().getMostSignificantBits());
            payload.putLong(input.inputId().getLeastSignificantBits());
            payload.put(bytes);
            return frame(1, requestId, payload.array());
        }
        if (command instanceof ControlCommand.Resize resize) {
            ByteBuffer payload = payload(8);
            payload.putInt(resize.columns()).putInt(resize.rows());
            return frame(2, requestId, payload.array());
        }
        if (command instanceof ControlCommand.Signal signal) {
            ByteBuffer payload = payload(8);
            payload.putShort((short) signal.kind().wireCode()).putShort((short) 0);
            payload.putInt(signal.platformCode());
            return frame(3, requestId, payload.array());
        }
        if (command instanceof ControlCommand.Terminate terminate) {
            ByteBuffer payload = payload(8);
            payload.putShort((short) terminate.mode().wireCode()).putShort((short) 0);
            payload.putInt((int) terminate.graceMillis());
            return frame(4, requestId, payload.array());
        }
        return frame(5, requestId, new byte[0]);
    }

    public ControlResult decode(ControlCommand command, long requestId, byte[] frame) {
        Optional<CommandId> commandId = command.commandId();
        String framingFailure = validateFrame(requestId, frame);
        if (framingFailure != null) {
            return new ControlResult.Failed(commandId, ControlResult.FailureKind.FRAMING, framingFailure);
        }
        ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        int type = Short.toUnsignedInt(header.getShort(8));
        int payloadLength = header.getInt(24);
        ByteBuffer payload = ByteBuffer.wrap(frame, HEADER_LENGTH, payloadLength)
                .slice().order(ByteOrder.LITTLE_ENDIAN);
        try {
            return switch (type) {
                case 0x8000 -> acknowledgement(command, payload, false);
                case 0x8001 -> acknowledgement(command, payload, true);
                case 0x8002 -> rejection(commandId, payload);
                case 0x8003 -> status(command, payload);
                default -> failed(commandId, "unsupported response message type " + type);
            };
        } catch (IllegalArgumentException error) {
            return failed(commandId, error.getMessage());
        }
    }

    static byte[] frame(int type, long requestId, byte[] payload) {
        if (payload.length > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("control payload exceeds 16 MiB");
        }
        ByteBuffer encoded = ByteBuffer.allocate(HEADER_LENGTH + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        encoded.put(MAGIC);
        encoded.putShort((short) VERSION);
        encoded.putShort((short) HEADER_LENGTH);
        encoded.putShort((short) type);
        encoded.putShort((short) 1);
        encoded.putInt(0);
        encoded.putLong(requestId);
        encoded.putInt(payload.length);
        encoded.putInt(checksum(payload));
        encoded.put(payload);
        return encoded.array();
    }

    private static ByteBuffer payload(int length) {
        if (length > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("control payload exceeds 16 MiB");
        }
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static String validateFrame(long requestId, byte[] frame) {
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
        if (header.getLong(16) != requestId) {
            return "response request ID does not match";
        }
        int payloadLength = header.getInt(24);
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_LENGTH
                || frame.length != HEADER_LENGTH + payloadLength) {
            return "response payload length is invalid";
        }
        byte[] payload = java.util.Arrays.copyOfRange(frame, HEADER_LENGTH, frame.length);
        if (checksum(payload) != header.getInt(28)) {
            return "response payload checksum does not match";
        }
        return null;
    }

    private static ControlResult acknowledgement(
            ControlCommand command,
            ByteBuffer payload,
            boolean duplicate
    ) {
        if (payload.remaining() != Long.BYTES || command.commandId().isEmpty()) {
            throw new IllegalArgumentException("acknowledgement payload or command is invalid");
        }
        if (duplicate && !(command instanceof ControlCommand.Input)) {
            throw new IllegalArgumentException("duplicate response is valid only for INPUT");
        }
        long timestamp = payload.getLong();
        if (timestamp < 0) {
            throw new IllegalArgumentException("journal timestamp exceeds the supported range");
        }
        return new ControlResult.Acknowledged(command.commandId().orElseThrow(), duplicate, timestamp);
    }

    private static ControlResult rejection(Optional<CommandId> commandId, ByteBuffer payload) {
        if (payload.remaining() < Integer.BYTES || payload.remaining() > Integer.BYTES + 4096) {
            throw new IllegalArgumentException("ERROR payload length is invalid");
        }
        int code = payload.getInt();
        byte[] detailBytes = new byte[payload.remaining()];
        payload.get(detailBytes);
        try {
            String detail = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(detailBytes)).toString();
            return new ControlResult.Rejected(commandId, code, detail);
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("ERROR detail is not valid UTF-8");
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

    private static ControlResult failed(Optional<CommandId> commandId, String detail) {
        return new ControlResult.Failed(commandId, ControlResult.FailureKind.FRAMING, detail);
    }

    private static int checksum(byte[] payload) {
        CRC32C checksum = new CRC32C();
        checksum.update(payload, 0, payload.length);
        return (int) checksum.getValue();
    }
}
