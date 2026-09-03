package pro.deta.orion.agentd.session;

import org.junit.jupiter.api.Test;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.CommandId;
import pro.deta.orion.agent.protocol.ProtocolBytes;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NativeControlCodecTest {
    private static final CommandId COMMAND_ID = new CommandId("command-1");
    private static final long REQUEST_ID = 0x0102_0304_0506_0708L;
    private final NativeControlCodec codec = new NativeControlCodec();

    @Test
    void encodesInputUuidInNetworkOrderInsideALittleEndianFrame() {
        UUID inputId = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        ControlCommand.Input input = new ControlCommand.Input(
                COMMAND_ID, inputId, ProtocolBytes.copyOf(new byte[]{0, (byte) 0xff, 7}));

        byte[] frame = codec.encode(input, REQUEST_ID);

        ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(frame).startsWith('O', 'R', 'C', 'T');
        assertThat(Short.toUnsignedInt(header.getShort(4))).isEqualTo(1);
        assertThat(Short.toUnsignedInt(header.getShort(8))).isEqualTo(1);
        assertThat(header.getLong(16)).isEqualTo(REQUEST_ID);
        assertThat(header.getInt(24)).isEqualTo(19);
        assertThat(frame).containsSubsequence(hex("00112233445566778899aabbccddeeff"));
        assertThat(frame).endsWith(0, (byte) 0xff, 7);
    }

    @Test
    void encodesOtherNativeControlPayloads() {
        assertPayload(
                new ControlCommand.Resize(COMMAND_ID, 160, 50),
                2,
                hex("a000000032000000"));
        assertPayload(
                new ControlCommand.Signal(COMMAND_ID, AgentMessage.SignalKind.PLATFORM, 15),
                3,
                hex("ffff00000f000000"));
        assertPayload(
                new ControlCommand.Terminate(COMMAND_ID, AgentMessage.TerminationMode.GRACEFUL, 250),
                4,
                hex("00000000fa000000"));
        assertPayload(new ControlCommand.Status(), 5, new byte[0]);
    }

    @Test
    void decodesAcceptedDuplicateAndHostErrorWithOriginalCommandId() {
        ControlCommand.Input input = new ControlCommand.Input(
                COMMAND_ID, UUID.randomUUID(), ProtocolBytes.copyOf(new byte[]{1}));

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
        assertThat(frame).endsWith(expectedPayload);
    }

    private static byte[] response(int type, byte[] payload) {
        return NativeControlCodec.frame(type, REQUEST_ID, payload);
    }

    private static byte[] longBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }

    private static byte[] hex(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }

    private static void assertFailure(ControlResult result, ControlResult.FailureKind kind) {
        assertThat(result).isInstanceOf(ControlResult.Failed.class);
        assertThat(((ControlResult.Failed) result).kind()).isEqualTo(kind);
    }
}
