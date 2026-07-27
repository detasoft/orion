package pro.deta.orion.git.parser.wire.protocolv2.response;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitWireError;
import pro.deta.orion.git.parser.wire.GitWireOutcome;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GitLsRefsResponseMachineTest {
    private static final String HEAD_ID = "1".repeat(40);
    private static final String TAG_ID = "2".repeat(40);
    private static final String PEELED_ID = "3".repeat(40);

    @Test
    void parsesKnownAndUnknownLsRefsAttributes() {
        try (GitMinimalWireMachine machine =
                     GitMinimalWireMachine.forV2LsRefsResponse(UnpooledByteBufAllocator.DEFAULT)) {
            ByteBuf input = transcript(
                    line(HEAD_ID + " HEAD symref-target:refs/heads/main custom:value"),
                    line(TAG_ID + " refs/tags/v1 peeled:" + PEELED_ID),
                    line("unborn refs/heads/new"),
                    "0000",
                    "0002");

            acceptAndRelease(machine, input);

            GitLsRefsResponse result = machine.result(GitLsRefsResponse.class);
            assertThat(result.refs()).hasSize(3);
            assertThat(result.refs().get(0).objectId()).contains(HEAD_ID);
            assertThat(result.refs().get(0).symrefTarget()).contains("refs/heads/main");
            assertThat(result.refs().get(0).unknownAttributes())
                    .containsExactly(new GitLsRefAttribute("custom", "value", "custom:value"));
            assertThat(result.refs().get(1).peeledObjectId()).contains(PEELED_ID);
            assertThat(result.refs().get(2).unborn()).isTrue();
            assertThat(result.refs().get(2).objectId()).isEmpty();
        }
    }

    @Test
    void parsesLsRefsResponseAcrossFragmentedHeadersPayloadsAndTerminals() {
        try (GitMinimalWireMachine machine =
                     GitMinimalWireMachine.forV2LsRefsResponse(UnpooledByteBufAllocator.DEFAULT)) {
            ByteBuf input = transcript(
                    line(HEAD_ID + " HEAD symref-target:refs/heads/main"),
                    "0000",
                    "0002");
            ByteBuf first = input.readRetainedSlice(2);
            ByteBuf second = input.readRetainedSlice(19);
            ByteBuf third = input.readRetainedSlice(input.readableBytes() - 3);
            ByteBuf fourth = input.readRetainedSlice(input.readableBytes());
            input.release();

            acceptAndRelease(machine, first);
            acceptAndRelease(machine, second);
            acceptAndRelease(machine, third);
            acceptAndRelease(machine, fourth);

            GitLsRefsResponse result = machine.result(GitLsRefsResponse.class);
            assertThat(result.refs()).singleElement()
                    .satisfies(ref -> {
                        assertThat(ref.name()).isEqualTo("HEAD");
                        assertThat(ref.symrefTarget()).contains("refs/heads/main");
                    });
        }
    }

    @Test
    void rejectsDuplicateRefsAndKnownAttributes() {
        try (GitMinimalWireMachine duplicateRef =
                     GitMinimalWireMachine.forV2LsRefsResponse(UnpooledByteBufAllocator.DEFAULT);
             GitMinimalWireMachine duplicateAttribute =
                     GitMinimalWireMachine.forV2LsRefsResponse(UnpooledByteBufAllocator.DEFAULT)) {
            acceptAndRelease(duplicateRef, transcript(
                    line(HEAD_ID + " HEAD"),
                    line(TAG_ID + " HEAD")));
            acceptAndRelease(duplicateAttribute, transcript(
                    line(HEAD_ID + " HEAD symref-target:refs/heads/main symref-target:refs/heads/other")));

            assertFailure(duplicateRef, 1);
            assertFailure(duplicateAttribute, 0);
        }
    }

    @Test
    void rejectsMalformedKnownAttributesAndPeeledUnbornRef() {
        try (GitMinimalWireMachine missingValue =
                     GitMinimalWireMachine.forV2LsRefsResponse(UnpooledByteBufAllocator.DEFAULT);
             GitMinimalWireMachine peeledUnborn =
                     GitMinimalWireMachine.forV2LsRefsResponse(UnpooledByteBufAllocator.DEFAULT)) {
            acceptAndRelease(missingValue, transcript(line(HEAD_ID + " HEAD peeled:")));
            acceptAndRelease(peeledUnborn, transcript(line("unborn HEAD peeled:" + PEELED_ID)));

            assertFailure(missingValue, 0);
            assertFailure(peeledUnborn, 0);
        }
    }

    @Test
    void responseEndCannotReplaceLsRefsFlush() {
        try (GitMinimalWireMachine machine =
                     GitMinimalWireMachine.forV2LsRefsResponse(UnpooledByteBufAllocator.DEFAULT)) {
            acceptAndRelease(machine, transcript(line(HEAD_ID + " HEAD"), "0002"));

            assertFailure(machine, 1);
        }
    }

    @Test
    void closeAfterFlushStoresMissingResponseEndFailure() {
        GitMinimalWireMachine machine =
                GitMinimalWireMachine.forV2LsRefsResponse(UnpooledByteBufAllocator.DEFAULT);
        acceptAndRelease(machine, transcript(line(HEAD_ID + " HEAD"), "0000"));

        machine.close();

        assertFailure(machine, 2);
    }

    @Test
    void rejectsInvalidGitRefName() {
        try (GitMinimalWireMachine machine =
                     GitMinimalWireMachine.forV2LsRefsResponse(UnpooledByteBufAllocator.DEFAULT)) {
            acceptAndRelease(machine, transcript(line(HEAD_ID + " refs//heads/main.lock")));

            assertFailure(machine, 0);
        }
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
        assertThat(machine.outcome(GitLsRefsResponse.class))
                .hasValueSatisfying(outcome -> {
                    GitWireOutcome.Failure<GitLsRefsResponse> failure =
                            (GitWireOutcome.Failure<GitLsRefsResponse>) outcome;
                    assertThat(failure.failure().error().kind())
                            .isEqualTo(GitWireError.Kind.INVALID_PROTOCOL_V2_RESPONSE);
                    assertThat(failure.failure().error().phase())
                            .isEqualTo(GitWireError.Phase.LS_REFS_RESPONSE);
                    assertThat(failure.failure().error().packetIndex()).isEqualTo(packetIndex);
                });
    }
}
