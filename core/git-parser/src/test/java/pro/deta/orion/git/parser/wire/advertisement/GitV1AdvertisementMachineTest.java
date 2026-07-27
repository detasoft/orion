package pro.deta.orion.git.parser.wire.advertisement;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitWireError;
import pro.deta.orion.git.parser.wire.GitWireOutcome;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GitV1AdvertisementMachineTest {
    private static final String HEAD_ID = "1".repeat(40);
    private static final String BRANCH_ID = "2".repeat(40);
    private static final String TAG_ID = "3".repeat(40);
    private static final String PEELED_ID = "4".repeat(40);

    @Test
    void parsesCapabilitiesRefsAndPeeledTags() {
        try (GitMinimalWireMachine machine =
                     GitMinimalWireMachine.forV1Advertisement(UnpooledByteBufAllocator.DEFAULT)) {
            ByteBuf input = transcript(
                    line(HEAD_ID + " HEAD\0multi_ack thin-pack"),
                    line(BRANCH_ID + " refs/heads/main"),
                    line(TAG_ID + " refs/tags/v1"),
                    line(PEELED_ID + " refs/tags/v1^{}"),
                    "0000");

            acceptAndRelease(machine, input);

            GitV1Advertisement result = machine.result(GitV1Advertisement.class);
            assertThat(result.capabilities().names()).containsExactly("multi_ack", "thin-pack");
            assertThat(result.refs()).extracting(GitAdvertisedRef::name)
                    .containsExactly("HEAD", "refs/heads/main", "refs/tags/v1");
            assertThat(result.refs().get(2).peeledObjectId()).contains(PEELED_ID);
            assertThat(result.emptyRepository()).isFalse();
        }
    }

    @Test
    void parsesAdvertisementFragmentedInsideHeaderCapabilitiesAndRefPayload() {
        try (GitMinimalWireMachine machine =
                     GitMinimalWireMachine.forV1Advertisement(UnpooledByteBufAllocator.DEFAULT)) {
            ByteBuf input = transcript(
                    line(HEAD_ID + " HEAD\0multi_ack thin-pack"),
                    line(BRANCH_ID + " refs/heads/main"),
                    "0000");
            ByteBuf first = input.readRetainedSlice(2);
            ByteBuf second = input.readRetainedSlice(55);
            ByteBuf third = input.readRetainedSlice(13);
            ByteBuf fourth = input.readRetainedSlice(input.readableBytes());
            input.release();

            acceptAndRelease(machine, first);
            acceptAndRelease(machine, second);
            acceptAndRelease(machine, third);
            acceptAndRelease(machine, fourth);

            GitV1Advertisement result = machine.result(GitV1Advertisement.class);
            assertThat(result.capabilities().names()).containsExactly("multi_ack", "thin-pack");
            assertThat(result.refs()).extracting(GitAdvertisedRef::name)
                    .containsExactly("HEAD", "refs/heads/main");
        }
    }

    @Test
    void parsesEmptyRepositorySentinel() {
        try (GitMinimalWireMachine machine =
                     GitMinimalWireMachine.forV1Advertisement(UnpooledByteBufAllocator.DEFAULT)) {
            ByteBuf input = transcript(
                    line("0".repeat(40) + " capabilities^{}\0report-status agent=orion"),
                    "0000");

            acceptAndRelease(machine, input);

            GitV1Advertisement result = machine.result(GitV1Advertisement.class);
            assertThat(result.emptyRepository()).isTrue();
            assertThat(result.refs()).isEmpty();
            assertThat(result.capabilities().names()).containsExactly("report-status", "agent");
        }
    }

    @Test
    void rejectsCapabilitiesOnASecondAdvertisementRow() {
        try (GitMinimalWireMachine machine =
                     GitMinimalWireMachine.forV1Advertisement(UnpooledByteBufAllocator.DEFAULT)) {
            ByteBuf input = transcript(
                    line(HEAD_ID + " HEAD\0multi_ack"),
                    line(BRANCH_ID + " refs/heads/main\0thin-pack"));

            acceptAndRelease(machine, input);

            assertFailure(machine, 1);
        }
    }

    @Test
    void rejectsPeeledRowWithoutItsBaseRef() {
        try (GitMinimalWireMachine machine =
                     GitMinimalWireMachine.forV1Advertisement(UnpooledByteBufAllocator.DEFAULT)) {
            ByteBuf input = transcript(
                    line(HEAD_ID + " HEAD"),
                    line(PEELED_ID + " refs/tags/missing^{}"));

            acceptAndRelease(machine, input);

            assertFailure(machine, 1);
        }
    }

    @Test
    void rejectsMalformedObjectIdAndDuplicateRef() {
        try (GitMinimalWireMachine malformed =
                     GitMinimalWireMachine.forV1Advertisement(UnpooledByteBufAllocator.DEFAULT);
             GitMinimalWireMachine duplicate =
                     GitMinimalWireMachine.forV1Advertisement(UnpooledByteBufAllocator.DEFAULT)) {
            acceptAndRelease(malformed, transcript(line("z".repeat(40) + " HEAD")));
            acceptAndRelease(duplicate, transcript(
                    line(HEAD_ID + " HEAD"),
                    line(BRANCH_ID + " HEAD")));

            assertFailure(malformed, 0);
            assertFailure(duplicate, 1);
        }
    }

    @Test
    void responseEndCannotReplaceAdvertisementFlush() {
        try (GitMinimalWireMachine machine =
                     GitMinimalWireMachine.forV1Advertisement(UnpooledByteBufAllocator.DEFAULT)) {
            acceptAndRelease(machine, transcript(line(HEAD_ID + " HEAD"), "0002"));

            assertFailure(machine, 1);
        }
    }

    @Test
    void closeBeforeFlushStoresAdvertisementFailure() {
        GitMinimalWireMachine machine =
                GitMinimalWireMachine.forV1Advertisement(UnpooledByteBufAllocator.DEFAULT);
        acceptAndRelease(machine, transcript(line(HEAD_ID + " HEAD")));

        machine.close();

        assertFailure(machine, 1);
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

    private static void acceptAndRelease(GitMinimalWireMachine machine, ByteBuf input) {
        if (machine.accept(input)) {
            input.release();
        }
    }

    private static void assertFailure(GitMinimalWireMachine machine, long packetIndex) {
        assertThat(machine.outcome(GitV1Advertisement.class))
                .hasValueSatisfying(outcome -> {
                    GitWireOutcome.Failure<GitV1Advertisement> failure =
                            (GitWireOutcome.Failure<GitV1Advertisement>) outcome;
                    assertThat(failure.failure().error().kind()).isEqualTo(GitWireError.Kind.INVALID_ADVERTISEMENT);
                    assertThat(failure.failure().error().phase()).isEqualTo(GitWireError.Phase.ADVERTISEMENT);
                    assertThat(failure.failure().error().packetIndex()).isEqualTo(packetIndex);
                });
    }
}
