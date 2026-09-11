package pro.deta.orion.agentd.session;

import org.junit.jupiter.api.Test;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionCommandSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class NativeControlCodecTest {
    private static final ProtocolBytes ENVELOPE = ProtocolBytes.copyOf(new byte[]{0x11});
    private static final Optional<ProtocolBytes> SERVER_ENVELOPE = Optional.of(ENVELOPE);
    private static final long SEQUENCE = 0x0102_0304_0506_0708L;
    private final NativeControlCodec codec = new NativeControlCodec();

    @Test
    void matchesSharedProcessControlsAndRejectsMalformedLists() throws IOException {
        List<byte[]> frames = splitFrames(Files.readAllBytes(Path.of(
                "../session-host/protocol/fixtures/process-controls.bin")));
        ControlCommand.ListProcesses list = new ControlCommand.ListProcesses();
        assertThat(codec.encode(list)).isEqualTo(frames.get(0));
        assertThat(codec.decode(list, frames.get(1))).isEqualTo(new ControlResult.Processes(List.of(
                new ControlResult.Process(1, 41, true), new ControlResult.Process(-1, 42, false))));
        for (int index = 0; index < 2; index++) {
            SessionCommandSource source = index == 0 ? SessionCommandSource.SERVER : SessionCommandSource.MANUAL;
            Optional<ProtocolBytes> envelope = index == 0
                    ? Optional.of(ProtocolBytes.copyOf("opaque".getBytes(StandardCharsets.UTF_8))) : Optional.empty();
            assertThat(codec.encode(new ControlCommand.Signal(7, source, envelope,
                    AgentMessage.SignalKind.INTERRUPT, -1, OptionalLong.of(-1)))).isEqualTo(frames.get(2 + index));
        }
        byte[] validPayload = java.util.Arrays.copyOfRange(frames.get(1), 32, frames.get(1).length);
        for (int offset : new int[]{0, 4, 12, 20, 24}) {
            byte[] invalid = validPayload.clone();
            ByteBuffer bytes = ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN);
            if (offset == 0) {
                bytes.putInt(offset, -1);
            } else if (offset == 4 || offset == 12) {
                bytes.putLong(offset, 0);
            } else {
                bytes.putInt(offset, 2);
            }
            assertThat(codec.decode(list, response(0x8004, 1, invalid))).isInstanceOf(ControlResult.Failed.class);
        }
        assertThat(codec.decode(new ControlCommand.Status(), frames.get(1)))
                .isInstanceOf(ControlResult.Failed.class);
        assertThat(codec.decode(list, response(0x8004, 1, new byte[4])))
                .isEqualTo(new ControlResult.Processes(List.of()));
        assertThat(codec.decode(list, response(0x8004, 1, new byte[]{1, 0, 0, 0})))
                .isInstanceOf(ControlResult.Failed.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new ControlCommand.Signal(1,
                SessionCommandSource.MANUAL, Optional.empty(), AgentMessage.SignalKind.INTERRUPT, -1,
                OptionalLong.of(0)));
    }

    @Test
    void encodesAllOperationEffectsForBothSources() {
        UUID inputId = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");

        assertOperation(
                new ControlCommand.Input(
                        SEQUENCE, SessionCommandSource.SERVER, SERVER_ENVELOPE,
                        inputId, ProtocolBytes.copyOf(new byte[]{0, (byte) 0xff, 7})),
                1,
                SessionCommandSource.SERVER,
                concat(concatHex("00112233445566778899aabbccddeeff"), new byte[]{0, (byte) 0xff, 7}));
        assertOperation(new ControlCommand.Resize(
                SEQUENCE, SessionCommandSource.MANUAL, Optional.empty(), 160, 50), 2,
                SessionCommandSource.MANUAL,
                concatHex("a000000032000000"));
        assertOperation(
                new ControlCommand.Signal(
                        SEQUENCE, SessionCommandSource.SERVER, SERVER_ENVELOPE,
                        AgentMessage.SignalKind.INTERRUPT, -1, OptionalLong.empty()),
                3,
                SessionCommandSource.SERVER,
                concatHex("01000000ffffffff"));
        assertOperation(
                new ControlCommand.Terminate(
                        SEQUENCE, SessionCommandSource.MANUAL, Optional.empty(),
                        AgentMessage.TerminationMode.GRACEFUL),
                4,
                SessionCommandSource.MANUAL,
                concatHex("00000000"));
    }

    @Test
    void matchesSharedEventIdOnlyJournalAcknowledgementFixture() throws IOException {
        List<byte[]> frames = splitFrames(Files.readAllBytes(Path.of(
                "../session-host/protocol/fixtures/journal-acknowledgement.bin")));
        assertThat(frames).hasSize(2);
        ControlCommand.AckJournal acknowledgement = new ControlCommand.AckJournal(-2);
        byte[] encoded = codec.encode(acknowledgement);
        ByteBuffer frame = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);

        assertThat(encoded).isEqualTo(frames.getFirst());
        assertThat(Short.toUnsignedInt(frame.getShort(8))).isEqualTo(7);
        assertThat(Short.toUnsignedInt(frame.getShort(10))).isEqualTo(1);
        assertThat(frame.getLong(16)).isEqualTo(1);
        assertThat(frame.getInt(24)).isEqualTo(Long.BYTES);
        assertThat(frame.getLong(NativeControlCodec.HEADER_LENGTH)).isEqualTo(-2);
        assertThat(codec.decode(acknowledgement, frames.getLast()))
                .isEqualTo(new ControlResult.JournalAcknowledged(-2));
        assertThatIllegalArgumentException().isThrownBy(() -> new ControlCommand.AckJournal(0));
        assertThatIllegalArgumentException().isThrownBy(() -> new ControlCommand.AckJournal(-1));
    }

    @Test
    void matchesEveryRequestInTheSharedNativeFixture() throws IOException {
        List<byte[]> frames = splitFrames(Files.readAllBytes(Path.of(
                "../session-host/protocol/fixtures/control-source-aware.bin")));
        assertThat(frames).hasSize(8);

        for (byte[] frame : frames) {
            ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
            int type = Short.toUnsignedInt(header.getShort(8));
            long sequence = header.getLong(16);
            ByteBuffer payload = ByteBuffer.wrap(frame, NativeControlCodec.HEADER_LENGTH, header.getInt(24))
                    .slice().order(ByteOrder.LITTLE_ENDIAN);
            int sourceIndex = Short.toUnsignedInt(payload.getShort()) - 1;
            SessionCommandSource source = SessionCommandSource.values()[sourceIndex];
            assertThat(payload.getShort()).isZero();
            byte[] envelope = new byte[payload.getInt()];
            payload.get(envelope);
            byte[] effect = new byte[payload.remaining()];
            payload.get(effect);

            assertThat(sequence).isNegative();
            assertThat(sequence).isNotEqualTo(-1);
            if (source == SessionCommandSource.SERVER) {
                assertThat(envelope).contains((byte) 0x66, (byte) 'f', (byte) 'u', (byte) 't');
            }
            assertThat(codec.encode(command(type, sequence, source, envelope, effect))).isEqualTo(frame);
        }
    }

    @Test
    void matchesSharedSequenceIndependentServerControlClaimFixture() throws IOException {
        List<byte[]> frames = splitFrames(Files.readAllBytes(Path.of(
                "../session-host/protocol/fixtures/server-sequence-recovery.bin")));
        assertThat(frames).hasSize(2);
        ByteBuffer.wrap(frames.get(0)).order(ByteOrder.LITTLE_ENDIAN).putLong(16, 1);
        ByteBuffer.wrap(frames.get(1)).order(ByteOrder.LITTLE_ENDIAN).putLong(16, 1);

        ControlCommand.ClaimServerControl claim = new ControlCommand.ClaimServerControl();
        assertThat(codec.encode(claim)).isEqualTo(frames.get(0));
        assertThat(codec.decode(claim, frames.get(1))).isEqualTo(
                new ControlResult.ServerControlClaimed());

        assertThat(codec.decode(claim, response(0x8005, 1, new byte[8])))
                .isInstanceOf(ControlResult.Failed.class);
        assertThat(codec.decode(new ControlCommand.Status(), frames.get(1)))
                .isInstanceOf(ControlResult.Failed.class);
    }

    @Test
    void decodesReceivedAsTransientAdmissionOrTypedRejection() {
        ControlCommand.Resize resize = serverResize(SEQUENCE, 80, 24);
        byte[] error = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(4).put("exited".getBytes(StandardCharsets.UTF_8)).array();

        assertThat(codec.decode(resize, response(0x8000, SEQUENCE, new byte[0])))
                .isEqualTo(new ControlResult.Received(SEQUENCE));
        assertThat(codec.decode(resize, response(0x8000, SEQUENCE, error)))
                .isEqualTo(new ControlResult.Rejected(OptionalLong.of(SEQUENCE), 4, "exited"));
        assertThat(codec.decode(resize, response(0x8002, SEQUENCE, error)))
                .isEqualTo(new ControlResult.Rejected(OptionalLong.of(SEQUENCE), 4, "exited"));
    }

    @Test
    void decodesStatusWithFixedPerConnectionCorrelation() {
        byte[] payload = runningStatus();

        byte[] request = codec.encode(new ControlCommand.Status());
        ControlResult result = codec.decode(new ControlCommand.Status(), response(0x8003, 1, payload));

        ByteBuffer header = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(Short.toUnsignedInt(header.getShort(8))).isEqualTo(5);
        assertThat(Short.toUnsignedInt(header.getShort(10))).isEqualTo(1);
        assertThat(header.getLong(16)).isEqualTo(1);
        assertThat(result).isEqualTo(new ControlResult.Status(new HostStatus(
                HostStatus.State.RUNNING, true, true, true, 120, 40, 4242,
                OptionalLong.of(4343), java.util.OptionalInt.empty(),
                java.util.OptionalInt.empty(), 1, 1)));
    }

    @Test
    void reportsMismatchedSequenceChecksumAndMalformedReceivedAsFramingFailures() {
        ControlCommand.Resize resize = serverResize(SEQUENCE, 80, 24);
        byte[] wrongSequence = response(0x8000, SEQUENCE + 1, new byte[0]);
        byte[] corrupt = response(0x8000, SEQUENCE, new byte[0]);
        corrupt[28] ^= 1;
        byte[] malformedError = response(0x8000, SEQUENCE, new byte[]{1});

        assertFailure(codec.decode(resize, wrongSequence), ControlResult.FailureKind.FRAMING);
        assertFailure(codec.decode(resize, corrupt), ControlResult.FailureKind.FRAMING);
        assertFailure(codec.decode(resize, malformedError), ControlResult.FailureKind.FRAMING);
    }

    @Test
    void rejectsOversizedResponseHeaderAndZeroChildPidInStatus() {
        byte[] oversized = response(0x8000, SEQUENCE, new byte[0]);
        ByteBuffer.wrap(oversized).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(24, NativeControlCodec.MAX_PAYLOAD_LENGTH + 1);
        assertFailure(
                codec.decode(serverResize(SEQUENCE, 80, 24), oversized),
                ControlResult.FailureKind.FRAMING);

        byte[] statusPayload = runningStatus();
        ByteBuffer.wrap(statusPayload).order(ByteOrder.LITTLE_ENDIAN).putLong(20, 0);
        assertFailure(
                codec.decode(new ControlCommand.Status(), response(0x8003, 1, statusPayload)),
                ControlResult.FailureKind.FRAMING);
    }

    @Test
    void boundsTheCompleteSourceAwarePayload() {
        ProtocolBytes oversizedEnvelope =
                ProtocolBytes.copyOf(new byte[NativeControlCodec.MAX_PAYLOAD_LENGTH]);
        ControlCommand.Resize resize = new ControlCommand.Resize(
                -2, SessionCommandSource.SERVER, Optional.of(oversizedEnvelope), 80, 24);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.encode(resize))
                .withMessageContaining("16 MiB");
    }

    @Test
    void reservesOnlyZeroAndUnsignedMaxForOperationSequences() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> serverResize(0, 80, 24));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> serverResize(-1, 80, 24));

        assertThat(codec.encode(serverResize(-2, 80, 24))).isNotEmpty();
    }

    @Test
    void requiresOnlyServerCommandsToCarryAServerEnvelope() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ControlCommand.Resize(
                        1, SessionCommandSource.SERVER, Optional.empty(), 80, 24));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ControlCommand.Resize(
                        1, SessionCommandSource.MANUAL, SERVER_ENVELOPE, 80, 24));
    }

    private void assertOperation(
            ControlCommand command, int type, SessionCommandSource source, byte[] expectedEffect) {
        byte[] frame = codec.encode(command);
        ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(Short.toUnsignedInt(header.getShort(8))).isEqualTo(type);
        assertThat(Short.toUnsignedInt(header.getShort(10))).isEqualTo(3);
        assertThat(header.getLong(16)).isEqualTo(SEQUENCE);
        ByteBuffer payload = ByteBuffer.wrap(frame, NativeControlCodec.HEADER_LENGTH, header.getInt(24))
                .slice().order(ByteOrder.LITTLE_ENDIAN);
        assertThat(Short.toUnsignedInt(payload.getShort())).isEqualTo(source.wireCode());
        assertThat(payload.getShort()).isZero();
        byte[] envelope = new byte[payload.getInt()];
        payload.get(envelope);
        byte[] effect = new byte[payload.remaining()];
        payload.get(effect);
        assertThat(envelope).isEqualTo(
                source == SessionCommandSource.SERVER ? ENVELOPE.toByteArray() : new byte[0]);
        assertThat(effect).isEqualTo(expectedEffect);
    }

    private static ControlCommand command(
            int type, long sequence, SessionCommandSource source, byte[] envelope, byte[] effect) {
        Optional<ProtocolBytes> serverEnvelope = source == SessionCommandSource.SERVER
                ? Optional.of(ProtocolBytes.copyOf(envelope)) : Optional.empty();
        ByteBuffer decoded = ByteBuffer.wrap(effect).order(ByteOrder.LITTLE_ENDIAN);
        return switch (type) {
            case 1 -> {
                ByteBuffer input = decoded.order(ByteOrder.BIG_ENDIAN);
                UUID inputId = new UUID(input.getLong(), input.getLong());
                byte[] bytes = new byte[input.remaining()];
                input.get(bytes);
                yield new ControlCommand.Input(
                        sequence, source, serverEnvelope, inputId, ProtocolBytes.copyOf(bytes));
            }
            case 2 -> new ControlCommand.Resize(
                    sequence, source, serverEnvelope, decoded.getInt(), decoded.getInt());
            case 3 -> {
                AgentMessage.SignalKind kind =
                        AgentMessage.SignalKind.fromWireCode(Short.toUnsignedInt(decoded.getShort()));
                decoded.getShort();
                yield new ControlCommand.Signal(
                        sequence, source, serverEnvelope, kind, decoded.getInt(), OptionalLong.empty());
            }
            case 4 -> {
                AgentMessage.TerminationMode mode =
                        AgentMessage.TerminationMode.fromWireCode(Short.toUnsignedInt(decoded.getShort()));
                decoded.getShort();
                yield new ControlCommand.Terminate(sequence, source, serverEnvelope, mode);
            }
            default -> throw new IllegalArgumentException("unexpected fixture message type " + type);
        };
    }

    private static ControlCommand.Resize serverResize(long sequence, int columns, int rows) {
        return new ControlCommand.Resize(
                sequence, SessionCommandSource.SERVER, SERVER_ENVELOPE, columns, rows);
    }

    private static List<byte[]> splitFrames(byte[] data) {
        List<byte[]> frames = new ArrayList<>();
        ByteBuffer cursor = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        while (cursor.hasRemaining()) {
            if (cursor.remaining() < NativeControlCodec.HEADER_LENGTH) {
                throw new IllegalArgumentException("fixture frame has incomplete header");
            }
            int start = cursor.position();
            int payloadLength = cursor.getInt(start + 24);
            int frameLength = NativeControlCodec.HEADER_LENGTH + payloadLength;
            if (payloadLength < 0 || payloadLength > NativeControlCodec.MAX_PAYLOAD_LENGTH
                    || cursor.remaining() < frameLength) {
                throw new IllegalArgumentException("fixture payload is invalid");
            }
            byte[] frame = new byte[frameLength];
            cursor.get(frame);
            frames.add(frame);
        }
        return frames;
    }

    private static byte[] response(int type, long sequence, byte[] payload) {
        return NativeControlCodec.frame(type, sequence, payload);
    }

    private static byte[] runningStatus() {
        byte[] payload = new byte[64];
        ByteBuffer status = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        status.putShort(0, (short) 2).putShort(2, (short) 7);
        status.putInt(4, 120).putInt(8, 40);
        status.putLong(12, 4242).putLong(20, 4343);
        status.putInt(44, Integer.MIN_VALUE).putInt(48, -1);
        status.putShort(52, (short) 1).putShort(54, (short) 1);
        return payload;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static byte[] concatHex(String hexadecimal) {
        return java.util.HexFormat.of().parseHex(hexadecimal);
    }

    private static void assertFailure(ControlResult result, ControlResult.FailureKind kind) {
        assertThat(result).isInstanceOf(ControlResult.Failed.class);
        assertThat(((ControlResult.Failed) result).kind()).isEqualTo(kind);
    }
}
