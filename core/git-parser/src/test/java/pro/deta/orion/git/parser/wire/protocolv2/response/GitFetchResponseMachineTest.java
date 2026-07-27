package pro.deta.orion.git.parser.wire.protocolv2.response;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitWireError;
import pro.deta.orion.git.parser.wire.GitWireOutcome;
import pro.deta.orion.git.parser.wire.utils.RawSink;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitFetchResponseMachineTest {
    private static final String ACK_ID = "1".repeat(40);
    private static final String SECOND_ACK_ID = "2".repeat(40);
    private static final String SHALLOW_ID = "3".repeat(40);
    private static final String UNSHALLOW_ID = "4".repeat(40);
    private static final String WANTED_ID = "5".repeat(40);

    @Test
    void parsesAcknowledgmentsOnlyNegotiationResponse() {
        try (GitMinimalWireMachine machine = fetchMachine()) {
            ByteBuf input = transcript(
                    line("acknowledgments"),
                    line("ACK " + ACK_ID),
                    line("ACK " + SECOND_ACK_ID),
                    line("ready"),
                    "0000",
                    "0002");

            acceptAndRelease(machine, input);

            GitFetchResponse result = machine.result(GitFetchResponse.class);
            assertThat(result.acknowledgments()).hasValueSatisfying(acknowledgments -> {
                assertThat(acknowledgments.objectIds()).containsExactly(ACK_ID, SECOND_ACK_ID);
                assertThat(acknowledgments.nak()).isFalse();
                assertThat(acknowledgments.ready()).isTrue();
            });
            assertThat(result.sections()).containsExactly(GitFetchSection.ACKNOWLEDGMENTS);
            assertThat(result.packfileReceived()).isFalse();
        }
    }

    @Test
    void parsesNakNegotiationResponseAcrossFragments() {
        try (GitMinimalWireMachine machine = fetchMachine()) {
            ByteBuf input = transcript(
                    line("acknowledgments"),
                    line("NAK"),
                    "0000",
                    "0002");
            ByteBuf first = input.readRetainedSlice(3);
            ByteBuf second = input.readRetainedSlice(17);
            ByteBuf third = input.readRetainedSlice(input.readableBytes());
            input.release();

            acceptAndRelease(machine, first);
            acceptAndRelease(machine, second);
            acceptAndRelease(machine, third);

            GitFetchAcknowledgments acknowledgments =
                    machine.result(GitFetchResponse.class).acknowledgments().orElseThrow();
            assertThat(acknowledgments.nak()).isTrue();
            assertThat(acknowledgments.objectIds()).isEmpty();
            assertThat(acknowledgments.ready()).isFalse();
        }
    }

    @Test
    void rejectsMixedAckAndNak() {
        try (GitMinimalWireMachine machine = fetchMachine()) {
            acceptAndRelease(machine, transcript(
                    line("acknowledgments"),
                    line("ACK " + ACK_ID),
                    line("NAK")));

            assertThat(machine.outcome(GitFetchResponse.class))
                    .hasValueSatisfying(outcome -> {
                        GitWireOutcome.Failure<GitFetchResponse> failure =
                                (GitWireOutcome.Failure<GitFetchResponse>) outcome;
                        assertThat(failure.failure().error().kind())
                                .isEqualTo(GitWireError.Kind.INVALID_PROTOCOL_V2_RESPONSE);
                        assertThat(failure.failure().error().phase())
                                .isEqualTo(GitWireError.Phase.FETCH_RESPONSE);
                        assertThat(failure.failure().error().packetIndex()).isEqualTo(2);
                    });
        }
    }

    @Test
    void parsesOrderedSectionsAndStreamsPackfileSideBand() {
        RecordingRawTarget rawTarget = new RecordingRawTarget();
        List<String> progress = new ArrayList<>();
        try (GitMinimalWireMachine machine = fetchMachine(rawTarget, progress)) {
            ByteBuf input = transcript(
                    line("acknowledgments"),
                    line("ACK " + ACK_ID),
                    line("ready"),
                    "0001",
                    line("shallow-info"),
                    line("shallow " + SHALLOW_ID),
                    line("unshallow " + UNSHALLOW_ID),
                    "0001",
                    line("wanted-refs"),
                    line(WANTED_ID + " refs/heads/main"),
                    "0001",
                    line("packfile"));
            append(input, sideBandPacket(1, new byte[]{'P', 'A', 'C', 'K'}));
            append(input, sideBandPacket(2, "counting\n".getBytes(StandardCharsets.UTF_8)));
            append(input, ascii("00000002"));

            acceptAndRelease(machine, input);

            GitFetchResponse result = machine.result(GitFetchResponse.class);
            assertThat(result.shallowInfo()).hasValueSatisfying(info -> {
                assertThat(info.shallowObjectIds()).containsExactly(SHALLOW_ID);
                assertThat(info.unshallowObjectIds()).containsExactly(UNSHALLOW_ID);
            });
            assertThat(result.wantedRefs())
                    .containsExactly(new GitFetchWantedRef(WANTED_ID, "refs/heads/main"));
            assertThat(result.sections()).containsExactly(
                    GitFetchSection.ACKNOWLEDGMENTS,
                    GitFetchSection.SHALLOW_INFO,
                    GitFetchSection.WANTED_REFS,
                    GitFetchSection.PACKFILE);
            assertThat(result.packfileReceived()).isTrue();
            assertThat(rawTarget.bytes).containsExactly((byte) 'P', (byte) 'A', (byte) 'C', (byte) 'K');
            assertThat(progress).containsExactly("counting\n");
            assertThat(rawTarget.creations).isOne();
            assertThat(rawTarget.closes).isOne();
        }
    }

    @Test
    void rejectsOutOfOrderAndDuplicateFetchSections() {
        try (GitMinimalWireMachine outOfOrder = fetchMachine();
             GitMinimalWireMachine duplicate = fetchMachine()) {
            acceptAndRelease(outOfOrder, transcript(
                    line("wanted-refs"),
                    line(WANTED_ID + " refs/heads/main"),
                    "0001",
                    line("shallow-info")));
            acceptAndRelease(duplicate, transcript(
                    line("acknowledgments"),
                    line("NAK"),
                    "0001",
                    line("acknowledgments")));

            assertFetchFailure(outOfOrder);
            assertFetchFailure(duplicate);
        }
    }

    @Test
    void progressOnlyPackfileDoesNotCreateRawTarget() {
        RecordingRawTarget rawTarget = new RecordingRawTarget();
        List<String> progress = new ArrayList<>();
        try (GitMinimalWireMachine machine = fetchMachine(rawTarget, progress)) {
            ByteBuf input = transcript(line("packfile"));
            append(input, sideBandPacket(2, "waiting\n".getBytes(StandardCharsets.UTF_8)));
            append(input, ascii("00000002"));

            acceptAndRelease(machine, input);

            GitFetchResponse result = machine.result(GitFetchResponse.class);
            assertThat(result.sections()).containsExactly(GitFetchSection.PACKFILE);
            assertThat(result.packfileReceived()).isTrue();
            assertThat(progress).containsExactly("waiting\n");
            assertThat(rawTarget.creations).isZero();
            assertThat(rawTarget.closes).isZero();
        }
    }

    @Test
    void sideBandFatalBecomesMachineFailureWithoutCreatingRawTarget() {
        RecordingRawTarget rawTarget = new RecordingRawTarget();
        try (GitMinimalWireMachine machine = fetchMachine(rawTarget, new ArrayList<>())) {
            ByteBuf input = transcript(line("packfile"));
            append(input, sideBandPacket(3, "remote failed\n".getBytes(StandardCharsets.UTF_8)));

            acceptAndRelease(machine, input);

            assertThat(machine.outcome(GitFetchResponse.class))
                    .hasValueSatisfying(outcome -> {
                        GitWireOutcome.Failure<GitFetchResponse> failure =
                                (GitWireOutcome.Failure<GitFetchResponse>) outcome;
                        assertThat(failure.failure().error().kind())
                                .isEqualTo(GitWireError.Kind.SIDE_BAND_FATAL);
                        assertThat(failure.failure().error().phase())
                                .isEqualTo(GitWireError.Phase.SIDE_BAND);
                    });
            assertThat(rawTarget.creations).isZero();
        }
    }

    private static GitMinimalWireMachine fetchMachine() {
        return fetchMachine(new RecordingRawTarget(), new ArrayList<>());
    }

    private static GitMinimalWireMachine fetchMachine(
            RecordingRawTarget target,
            List<String> progress) {
        return GitMinimalWireMachine.forV2FetchResponse(
                UnpooledByteBufAllocator.DEFAULT,
                _control -> {
                    target.creations++;
                    return target;
                },
                progress::add);
    }

    private static String line(String payload) {
        String withLineFeed = payload + '\n';
        return "%04x%s".formatted(4 + withLineFeed.getBytes(StandardCharsets.UTF_8).length, withLineFeed);
    }

    private static ByteBuf transcript(String... packets) {
        StringBuilder result = new StringBuilder();
        for (String packet : packets) {
            result.append(packet);
        }
        return Unpooled.copiedBuffer(result, StandardCharsets.UTF_8);
    }

    private static ByteBuf sideBandPacket(int band, byte[] payload) {
        ByteBuf packet = Unpooled.buffer(5 + payload.length);
        packet.writeCharSequence("%04x".formatted(5 + payload.length), StandardCharsets.US_ASCII);
        packet.writeByte(band);
        packet.writeBytes(payload);
        return packet;
    }

    private static ByteBuf ascii(String value) {
        return Unpooled.copiedBuffer(value, StandardCharsets.US_ASCII);
    }

    private static void append(ByteBuf target, ByteBuf source) {
        try {
            target.writeBytes(source);
        } finally {
            source.release();
        }
    }

    private static void acceptAndRelease(GitMinimalWireMachine machine, ByteBuf input) {
        if (machine.accept(input)) {
            input.release();
        }
    }

    private static void assertFetchFailure(GitMinimalWireMachine machine) {
        assertThat(machine.outcome(GitFetchResponse.class))
                .hasValueSatisfying(outcome -> {
                    GitWireOutcome.Failure<GitFetchResponse> failure =
                            (GitWireOutcome.Failure<GitFetchResponse>) outcome;
                    assertThat(failure.failure().error().kind())
                            .isEqualTo(GitWireError.Kind.INVALID_PROTOCOL_V2_RESPONSE);
                    assertThat(failure.failure().error().phase())
                            .isEqualTo(GitWireError.Phase.FETCH_RESPONSE);
                });
    }

    private static final class RecordingRawTarget implements RawSink.Target {
        private final List<Byte> bytes = new ArrayList<>();
        private int creations;
        private int closes;

        @Override
        public void accept(ByteBuf input) {
            try {
                while (input.isReadable()) {
                    bytes.add(input.readByte());
                }
            } finally {
                input.release();
            }
        }

        @Override
        public void close() {
            closes++;
        }
    }
}
