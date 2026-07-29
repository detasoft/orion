package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitNativeClientOutputTest {
    private static final String MAIN_ID =
            "1111111111111111111111111111111111111111";
    private static final String TAG_ID =
            "2222222222222222222222222222222222222222";
    private static final String PEELED_TAG_ID =
            "3333333333333333333333333333333333333333";

    @Test
    void sendsTypedLegacyAdvertisementAsPktLines() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        GitV1Advertisement advertisement = new GitV1Advertisement(
                List.of(
                        GitCapability.MULTI_ACK,
                        GitCapability.agent("orion-native")),
                List.of(
                        GitAdvertisedRef.direct(MAIN_ID, "refs/heads/main"),
                        GitAdvertisedRef.direct(TAG_ID, "refs/tags/v1")
                                .withPeeledObjectId(PEELED_TAG_ID)));

        assertThat(output.sendAdvertisement(advertisement)).isTrue();

        try {
            assertThat(outbound.toString(StandardCharsets.UTF_8)).isEqualTo(
                    "005a" + MAIN_ID
                            + " refs/heads/main\0multi_ack agent=orion-native\n"
                            + "003a" + TAG_ID + " refs/tags/v1\n"
                            + "003d" + PEELED_TAG_ID + " refs/tags/v1^{}\n"
                            + "0000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void leavesBufferUnchangedWhenAdvertisementDoesNotFit() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        outbound.writerIndex(outbound.capacity() - 1);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        GitV1Advertisement advertisement = new GitV1Advertisement(
                List.of(),
                List.of(GitAdvertisedRef.direct(
                        MAIN_ID,
                        "refs/heads/main")));

        int writerIndex = outbound.writerIndex();
        try {
            assertThat(output.sendAdvertisement(advertisement)).isFalse();
            assertThat(outbound.writerIndex()).isEqualTo(writerIndex);
        } finally {
            outbound.release();
        }
    }

    @Test
    void isAvailableToWireContinuationsThroughContext() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            GitMinimalWireMachine.Context context =
                    GitMinimalWireMachine.testContext(
                            UnpooledByteBufAllocator.DEFAULT,
                            output);

            assertThat(context.clientOutput).isSameAs(output);
        } finally {
            outbound.release();
        }
    }
}
