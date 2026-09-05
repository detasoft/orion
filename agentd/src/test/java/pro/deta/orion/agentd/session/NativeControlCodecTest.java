package pro.deta.orion.agentd.session;

import org.junit.jupiter.api.Test;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.CommandId;
import pro.deta.orion.agent.protocol.ProtocolBytes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NativeControlCodecTest {
    private static final CommandId COMMAND_ID = new CommandId("command-1");
    private static final ProtocolBytes ENVELOPE = ProtocolBytes.copyOf(new byte[]{0x11});
    private static final long REQUEST_ID = 0x0102_0304_0506_0708L;
    private final NativeControlCodec codec = new NativeControlCodec();

    @Test
    void encodesInputUuidInNetworkOrderInsideALittleEndianSchema2Frame() {
        UUID inputId = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        ControlCommand.Input input = new ControlCommand.Input(
                COMMAND_ID,
                13,
                ENVELOPE,
                inputId,
                ProtocolBytes.copyOf(new byte[]{0, (byte) 0xff, 7}));

        byte[] frame = codec.encode(input, REQUEST_ID);

        ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(frame).startsWith('O', 'R', 'C', 'T');
        assertThat(Short.toUnsignedInt(header.getShort(4))).isEqualTo(1);
        assertThat(Short.toUnsignedInt(header.getShort(8))).isEqualTo(1);
        assertThat(Short.toUnsignedInt(header.getShort(10))).isEqualTo(2);
        assertThat(header.getLong(16)).isEqualTo(REQUEST_ID);
        assertThat(frame).containsSubsequence(concatHex("00112233445566778899aabbccddeeff"));
        assertThat(frame).endsWith(0, (byte) 0xff, 7);
    }

    @Test
    void encodesOtherNativeControlPayloadsAsSchemaTwoOperationWrappers() {
        assertPayload(
                new ControlCommand.Resize(
                        COMMAND_ID,
                        10,
                        ENVELOPE,
                        160,
                        50),
                2,
                expectedOperationPayload(
                        10,
                        COMMAND_ID,
                        ENVELOPE,
                        concatHex("a000000032000000")));
        assertPayload(
                new ControlCommand.Signal(
                        COMMAND_ID,
                        11,
                        ENVELOPE,
                        AgentMessage.SignalKind.INTERRUPT,
                        -1),
                3,
                expectedOperationPayload(
                        11,
                        COMMAND_ID,
                        ENVELOPE,
                        concatHex("01000000ffffffff")));
        assertPayload(
                new ControlCommand.Terminate(
                        COMMAND_ID,
                        12,
                        ENVELOPE,
                        AgentMessage.TerminationMode.GRACEFUL,
                        250),
                4,
                expectedOperationPayload(
                        12,
                        COMMAND_ID,
                        ENVELOPE,
                        concatHex("00000000fa000000")));
        assertPayload(new ControlCommand.Status(), 5, new byte[0]);
    }

    @Test
    void matchesSharedControlIdempotencyFixture() throws IOException {
        List<byte[]> frames = splitFrames(
                Files.readAllBytes(Path.of(
                        "../session-host/protocol/fixtures/control-idempotency-v2.bin")));
        assertThat(frames).hasSize(5);

        assertThat(Short.toUnsignedInt(headerField(frames.get(4), 8))).isEqualTo(7);
        assertThat(Short.toUnsignedInt(headerField(frames.get(4), 10))).isEqualTo(1);
        assertThat(headerFieldLong(frames.get(4), 16)).isEqualTo(74);

        String[] expectedCommandIds = {
                "command.input",
                "command.resize",
                "command.signal",
                "command.terminate"
        };
        for (int index = 0; index < 4; index++) {
            byte[] request = frames.get(index);
            ByteBuffer header = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN);
            assertThat(Short.toUnsignedInt(header.getShort(8))).isEqualTo(index + 1);
            assertThat(Short.toUnsignedInt(header.getShort(10))).isEqualTo(2);
            assertThat(header.getLong(16)).isEqualTo(70 + index);

            ByteBuffer payload = ByteBuffer.wrap(request,
                            NativeControlCodec.HEADER_LENGTH,
                            header.getInt(24)).slice().order(ByteOrder.LITTLE_ENDIAN);
            long operationSequence = payload.getLong();
            int commandIdLength = Short.toUnsignedInt(payload.getShort());
            byte[] commandIdBytes = new byte[commandIdLength];
            payload.get(commandIdBytes);
            int envelopeLength = payload.getInt();
            byte[] envelopeBytes = new byte[envelopeLength];
            payload.get(envelopeBytes);
            byte[] effect = new byte[payload.remaining()];
            payload.get(effect);

            assertThat(new String(commandIdBytes, StandardCharsets.UTF_8)).isEqualTo(expectedCommandIds[index]);

            ControlCommand command;
            if (index == 0) {
                ByteBuffer inputEffect = ByteBuffer.wrap(effect).order(ByteOrder.BIG_ENDIAN);
                UUID inputId = new UUID(inputEffect.getLong(), inputEffect.getLong());
                byte[] inputBytes = new byte[inputEffect.remaining()];
                inputEffect.get(inputBytes);
                command = new ControlCommand.Input(
                        new CommandId(new String(commandIdBytes, StandardCharsets.UTF_8)),
                        operationSequence,
                        ProtocolBytes.copyOf(envelopeBytes),
                        inputId,
                        ProtocolBytes.copyOf(inputBytes));
            } else if (index == 1) {
                ByteBuffer resize = ByteBuffer.wrap(effect).order(ByteOrder.LITTLE_ENDIAN);
                command = new ControlCommand.Resize(
                        new CommandId(new String(commandIdBytes, StandardCharsets.UTF_8)),
                        operationSequence,
                        ProtocolBytes.copyOf(envelopeBytes),
                        resize.getInt(),
                        resize.getInt());
            } else if (index == 2) {
                ByteBuffer signal = ByteBuffer.wrap(effect).order(ByteOrder.LITTLE_ENDIAN);
                AgentMessage.SignalKind signalKind =
                        AgentMessage.SignalKind.fromWireCode(Short.toUnsignedInt(signal.getShort()));
                signal.getShort();
                command = new ControlCommand.Signal(
                        new CommandId(new String(commandIdBytes, StandardCharsets.UTF_8)),
                        operationSequence,
                        ProtocolBytes.copyOf(envelopeBytes),
                        signalKind,
                        signal.getInt());
            } else {
                ByteBuffer terminate = ByteBuffer.wrap(effect).order(ByteOrder.LITTLE_ENDIAN);
                AgentMessage.TerminationMode terminationMode =
                        AgentMessage.TerminationMode.fromWireCode(Short.toUnsignedInt(terminate.getShort()));
                terminate.getShort();
                command = new ControlCommand.Terminate(
                        new CommandId(new String(commandIdBytes, StandardCharsets.UTF_8)),
                        operationSequence,
                        ProtocolBytes.copyOf(envelopeBytes),
                        terminationMode,
                        Integer.toUnsignedLong(terminate.getInt()));
            }
            assertThat(codec.encode(command, header.getLong(16))).isEqualTo(request);
        }
    }

    @Test
    void decodesAcceptedDuplicateAndHostErrorWithOriginalCommandId() {
        ControlCommand.Input input = new ControlCommand.Input(
                COMMAND_ID,
                1,
                ENVELOPE,
                UUID.randomUUID(),
                ProtocolBytes.copyOf(new byte[]{1}));

        ControlResult accepted = codec.decode(input, REQUEST_ID, response(0x8000, longBytes(42)));
        ControlResult duplicate = codec.decode(input, REQUEST_ID, response(0x8001, longBytes(41)));
        byte[] error = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(6).put("nope".getBytes(StandardCharsets.UTF_8)).array();
        ControlResult rejected = codec.decode(input, REQUEST_ID, response(0x8002, error));

        assertThat(accepted).isEqualTo(new ControlResult.Acknowledged(COMMAND_ID, false, 42));
        assertThat(duplicate).isEqualTo(new ControlResult.Acknowledged(COMMAND_ID, true, 41));
        assertThat(rejected).isEqualTo(new ControlResult.Rejected(Optional.of(COMMAND_ID), 6, "nope"));
    }

    @Test
    void decodesStatusWithoutExposingJournalTimestampBounds() {
        byte[] payload = new byte[64];
        ByteBuffer status = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        status.putShort(0, (short) 2);
        status.putShort(2, (short) 7);
        status.putInt(4, 120);
        status.putInt(8, 40);
        status.putLong(12, 4242);
        status.putLong(20, 4343);
        status.putInt(44, Integer.MIN_VALUE);
        status.putInt(48, -1);
        status.putShort(52, (short) 1);
        status.putShort(54, (short) 1);

        ControlResult result = codec.decode(new ControlCommand.Status(), REQUEST_ID, response(0x8003, payload));

        assertThat(result).isEqualTo(new ControlResult.Status(new HostStatus(
                HostStatus.State.RUNNING, true, true, true, 120, 40, 4242,
                java.util.OptionalLong.of(4343), java.util.OptionalInt.empty(),
                java.util.OptionalInt.empty(), 1, 1)));
    }

    @Test
    void rejectsWrongRequestIdChecksumAndOversizedPayloadAsTypedFailures() {
        ControlCommand.Status command = new ControlCommand.Status();
        byte[] wrongRequest = response(0x8003, new byte[64]);
        ByteBuffer.wrap(wrongRequest).order(ByteOrder.LITTLE_ENDIAN).putLong(16, REQUEST_ID + 1);
        byte[] corrupt = response(0x8000, longBytes(2));
        corrupt[corrupt.length - 1] ^= 1;

        assertFailure(codec.decode(command, REQUEST_ID, wrongRequest), ControlResult.FailureKind.FRAMING);
        assertFailure(codec.decode(command, REQUEST_ID, corrupt), ControlResult.FailureKind.FRAMING);

        byte[] oversizedHeader = response(0x8000, new byte[0]);
        ByteBuffer.wrap(oversizedHeader).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(24, NativeControlCodec.MAX_PAYLOAD_LENGTH + 1);
        assertFailure(codec.decode(command, REQUEST_ID, oversizedHeader), ControlResult.FailureKind.FRAMING);
    }

    @Test
    void rejectsZeroChildPidInStatus() {
        byte[] payload = new byte[64];
        ByteBuffer status = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        status.putShort(0, (short) 1).putShort(2, (short) 1);
        status.putInt(4, 80).putInt(8, 24);
        status.putLong(12, 4242).putLong(20, 0);
        status.putInt(44, Integer.MIN_VALUE).putInt(48, -1);
        status.putShort(52, (short) 1).putShort(54, (short) 1);

        ControlResult result = codec.decode(
                new ControlCommand.Status(), REQUEST_ID, response(0x8003, payload));

        assertFailure(result, ControlResult.FailureKind.FRAMING);
    }

    private void assertPayload(ControlCommand command, int type, byte[] expectedPayload) {
        byte[] frame = codec.encode(command, REQUEST_ID);
        ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(Short.toUnsignedInt(header.getShort(8))).isEqualTo(type);
        int expectedSchema = command instanceof ControlCommand.Status ? 1 : 2;
        assertThat(Short.toUnsignedInt(header.getShort(10))).isEqualTo(expectedSchema);
        assertThat(frame).startsWith('O', 'R', 'C', 'T');
        assertThat(header.getInt(24)).isEqualTo(expectedPayload.length);
        assertThat(frame).endsWith(expectedPayload);
    }

    private static List<byte[]> splitFrames(byte[] data) {
        List<byte[]> frames = new ArrayList<>();
        ByteBuffer cursor = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        while (cursor.hasRemaining()) {
            int remaining = cursor.remaining();
            if (remaining < NativeControlCodec.HEADER_LENGTH) {
                throw new IllegalArgumentException("fixture frame has incomplete header");
            }
            ByteBuffer header = ByteBuffer.allocate(NativeControlCodec.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < NativeControlCodec.HEADER_LENGTH; i++) {
                header.put(cursor.get());
            }
            int payloadLength = header.getInt(24);
            if (payloadLength < 0 || payloadLength > NativeControlCodec.MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException("fixture payload length is invalid");
            }
            if (cursor.remaining() < payloadLength) {
                throw new IllegalArgumentException("fixture payload is incomplete");
            }
            byte[] frame = new byte[NativeControlCodec.HEADER_LENGTH + payloadLength];
            System.arraycopy(header.array(), 0, frame, 0, NativeControlCodec.HEADER_LENGTH);
            for (int i = 0; i < payloadLength; i++) {
                frame[NativeControlCodec.HEADER_LENGTH + i] = cursor.get();
            }
            frames.add(frame);
        }
        return frames;
    }

    private static short headerField(byte[] frame, int offset) {
        return ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getShort(offset);
    }

    private static long headerFieldLong(byte[] frame, int offset) {
        return ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getLong(offset);
    }

    private static byte[] expectedOperationPayload(
            long sequence,
            CommandId commandId,
            ProtocolBytes commandEnvelope,
            byte[] effect
    ) {
        byte[] commandIdBytes = commandId.value().getBytes(StandardCharsets.UTF_8);
        byte[] envelopeBytes = commandEnvelope.toByteArray();
        ByteBuffer payload = ByteBuffer
                .allocate(Long.BYTES + Short.BYTES + commandIdBytes.length + Integer.BYTES + envelopeBytes.length
                        + effect.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        payload.putLong(sequence);
        payload.putShort((short) commandIdBytes.length);
        payload.put(commandIdBytes);
        payload.putInt(envelopeBytes.length);
        payload.put(envelopeBytes);
        payload.put(effect);
        return payload.array();
    }

    private static byte[] response(int type, byte[] payload) {
        return NativeControlCodec.frame(type, REQUEST_ID, payload);
    }

    private static byte[] longBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }

    private static byte[] concatHex(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }

    private static void assertFailure(ControlResult result, ControlResult.FailureKind kind) {
        assertThat(result).isInstanceOf(ControlResult.Failed.class);
        assertThat(((ControlResult.Failed) result).kind()).isEqualTo(kind);
    }
}
