package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.WrappedByteBuf;
import org.junit.jupiter.api.Test;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitNativeClientOutputTest {
    private static final String MAIN_ID =
            "1111111111111111111111111111111111111111";
    private static final String TAG_ID =
            "2222222222222222222222222222222222222222";
    private static final String PEELED_TAG_ID =
            "3333333333333333333333333333333333333333";

    @Test
    void mapsEverySendResultToAContinuationTransition() {
        Continuation<ByteBuf> next = input -> ContinuationFlow.await();
        Runnable task = () -> {
        };
        IllegalStateException failure =
                new IllegalStateException("failed");

        assertThat(new GitNativeClientOutput.SendResult.Completed()
                .transitionTo(next))
                .isEqualTo(ContinuationFlow.transition(next));
        assertThat(new GitNativeClientOutput.SendResult.Streaming(task)
                .transitionTo(next))
                .isInstanceOfSatisfying(
                        ContinuationFlow.TransitionAndYield.class,
                        yielded -> {
                            assertThat(yielded.task()).isSameAs(task);
                            yielded.task().run();
                            assertThat(yielded.next().process(null))
                                    .isEqualTo(
                                            ContinuationFlow.transition(next));
                        });
        assertThat(new GitNativeClientOutput.SendResult.Failed(
                "output failed",
                failure).transitionTo(next))
                .isInstanceOfSatisfying(
                        ContinuationFlow.Transition.class,
                        transition -> assertThat(transition.next())
                                .isInstanceOfSatisfying(
                                        Continuation.CompletedError.class,
                                        error -> {
                                            assertThat(error.message())
                                                    .isEqualTo(
                                                            "output failed");
                                            assertThat(error.throwable())
                                                    .isSameAs(failure);
                                        }));
    }

    @Test
    void sendsProtocolV2UploadPackAdvertisement() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            assertThat(output.sendV2UploadPackAdvertisement(
                    GitWireConfiguration.allSupported().protocolV2()))
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
            assertThat(outbound.toString(StandardCharsets.US_ASCII))
                    .isEqualTo(
                            "000eversion 2\n"
                                    + "0013ls-refs=unborn\n"
                                    + "0033fetch=shallow wait-for-done filter ref-in-want\n"
                                    + "0012server-option\n"
                                    + "0000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void sendsPlainLsRefsWhenUnbornIsDisabled() {
        assertV2Advertisement(
                new GitWireConfiguration.ProtocolV2(
                        true, false, false, false),
                "000eversion 2\n"
                        + "000cls-refs\n"
                        + "0000");
    }

    @Test
    void omitsDisabledProtocolV2Capabilities() {
        assertV2Advertisement(
                new GitWireConfiguration.ProtocolV2(
                        false, false, true, false, true),
                "000eversion 2\n"
                        + "000afetch\n"
                        + "0012server-option\n"
                        + "0000");
        assertV2Advertisement(
                new GitWireConfiguration.ProtocolV2(
                        false, false, true, true, false),
                "000eversion 2\n"
                        + "0018fetch=wait-for-done\n"
                        + "0000");
        assertV2Advertisement(
                new GitWireConfiguration.ProtocolV2(
                        false, false, true, true, false, false),
                "000eversion 2\n"
                        + "0012fetch=shallow\n"
                        + "0000");
        assertV2Advertisement(
                new GitWireConfiguration.ProtocolV2(
                        false, false, true, true, true, false),
                "000eversion 2\n"
                        + "0020fetch=shallow wait-for-done\n"
                        + "0000");
        assertV2Advertisement(
                new GitWireConfiguration.ProtocolV2(
                        false, false, true, false, false, false, true),
                "000eversion 2\n"
                        + "0011fetch=filter\n"
                        + "0000");
        assertV2Advertisement(
                new GitWireConfiguration.ProtocolV2(
                        false,
                        false,
                        true,
                        false,
                        false,
                        false,
                        false,
                        true),
                "000eversion 2\n"
                        + "0016fetch=ref-in-want\n"
                        + "0000");
        assertV2Advertisement(
                new GitWireConfiguration.ProtocolV2(
                        true, true, false, true),
                "000eversion 2\n"
                        + "0013ls-refs=unborn\n"
                        + "0012server-option\n"
                        + "0000");
        assertV2Advertisement(
                new GitWireConfiguration.ProtocolV2(
                        true, true, true, false),
                "000eversion 2\n"
                        + "0013ls-refs=unborn\n"
                        + "000afetch\n"
                        + "0000");
    }

    @Test
    void keepsProtocolV2CapabilitiesInStableOrder() {
        assertV2Advertisement(
                new GitWireConfiguration.ProtocolV2(
                        true, false, true, true, true, true, true, true),
                "000eversion 2\n"
                        + "000cls-refs\n"
                        + "0033fetch=shallow wait-for-done filter ref-in-want\n"
                        + "0012server-option\n"
                        + "0000");
    }

    @Test
    void reportsMissingProtocolV2Configuration() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            assertThat(output.sendV2UploadPackAdvertisement(null))
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> {
                                assertThat(failed.message())
                                        .isEqualTo(
                                                "Failed to serialize protocol v2 advertisement");
                                assertThat(failed.cause())
                                        .isInstanceOf(
                                                NullPointerException.class);
                            });
        } finally {
            outbound.release();
        }
    }

    private static void assertV2Advertisement(
            GitWireConfiguration.ProtocolV2 configuration,
            String expected) {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            assertThat(output.sendV2UploadPackAdvertisement(configuration))
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
            assertThat(outbound.toString(StandardCharsets.US_ASCII))
                    .isEqualTo(expected);
        } finally {
            outbound.release();
        }
    }

    @Test
    void sendsOrderedProtocolV2LsRefsRows() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        GitLsRefsResponse response = new GitLsRefsResponse(List.of(
                new GitLsRefsResponse.DirectRef(
                        MAIN_ID,
                        "refs/heads/main",
                        Optional.empty(),
                        Optional.empty()),
                new GitLsRefsResponse.DirectRef(
                        MAIN_ID,
                        "HEAD",
                        Optional.of("refs/heads/main"),
                        Optional.empty()),
                new GitLsRefsResponse.DirectRef(
                        TAG_ID,
                        "refs/tags/v1",
                        Optional.empty(),
                        Optional.of(PEELED_TAG_ID)),
                new GitLsRefsResponse.UnbornRef(
                        "refs/heads/new",
                        "refs/heads/main")));

        try {
            assertThat(output.sendLsRefs(response))
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
            assertThat(outbound.toString(StandardCharsets.US_ASCII))
                    .isEqualTo(
                            "003d" + MAIN_ID + " refs/heads/main\n"
                                    + "0050" + MAIN_ID
                                    + " HEAD symref-target:refs/heads/main\n"
                                    + "006a" + TAG_ID
                                    + " refs/tags/v1 peeled:"
                                    + PEELED_TAG_ID + "\n"
                                    + "0038unborn refs/heads/new"
                                    + " symref-target:refs/heads/main\n"
                                    + "0000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void sendsEmptyProtocolV2LsRefsResponseAsFlush() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            assertThat(output.sendLsRefs(
                    new GitLsRefsResponse(List.of())))
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
            assertThat(outbound.toString(StandardCharsets.US_ASCII))
                    .isEqualTo("0000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void streamsProtocolV2LsRefsWhenOutputIsAlreadyFull() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        outbound.writerIndex(outbound.capacity());
        outbound.setByte(outbound.writerIndex() - 1, 'x');
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = collectingOutput(outbound, sent);
        GitLsRefsResponse response = new GitLsRefsResponse(List.of(
                new GitLsRefsResponse.DirectRef(
                        MAIN_ID,
                        "refs/heads/main",
                        Optional.empty(),
                        Optional.empty())));

        GitNativeClientOutput.SendResult.Streaming streaming =
                (GitNativeClientOutput.SendResult.Streaming)
                        output.sendLsRefs(response);
        streaming.task().run();

        try {
            assertThat(sent).hasSize(2);
            assertThat(sent.getFirst()).hasSize(outbound.capacity());
            assertThat(sent.getFirst()[outbound.capacity() - 1])
                    .isEqualTo((byte) 'x');
            assertThat(new String(
                    sent.getLast(),
                    StandardCharsets.US_ASCII))
                    .isEqualTo(
                            "003d" + MAIN_ID
                                    + " refs/heads/main\n0000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void rejectsNonAsciiProtocolV2LsRefsRow() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        GitLsRefsResponse response = new GitLsRefsResponse(List.of(
                new GitLsRefsResponse.UnbornRef(
                        "refs/heads/caf\u00e9",
                        "refs/heads/main")));

        try {
            assertThat(output.sendLsRefs(response))
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> {
                                assertThat(failed.message()).isEqualTo(
                                        "Failed to serialize protocol v2"
                                                + " ls-refs response");
                                assertThat(failed.cause())
                                        .isInstanceOf(
                                                IllegalArgumentException.class);
                            });
            assertThat(outbound.writerIndex()).isZero();
        } finally {
            outbound.release();
        }
    }

    @Test
    void rejectsMalformedProtocolV2LsRefsObjectIds() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        List<GitLsRefsResponse> malformed = List.of(
                new GitLsRefsResponse(List.of(
                        new GitLsRefsResponse.DirectRef(
                                MAIN_ID.substring(1),
                                "refs/heads/main",
                                Optional.empty(),
                                Optional.empty()))),
                new GitLsRefsResponse(List.of(
                        new GitLsRefsResponse.DirectRef(
                                MAIN_ID,
                                "refs/tags/v1",
                                Optional.empty(),
                                Optional.of("g".repeat(40))))));

        try {
            for (GitLsRefsResponse response : malformed) {
                assertLsRefsSerializationFailed(output, response);
            }
            assertThat(outbound.writerIndex()).isZero();
        } finally {
            outbound.release();
        }
    }

    @Test
    void rejectsUnsafeProtocolV2LsRefsTokens() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        List<GitLsRefsResponse> malformed = List.of(
                new GitLsRefsResponse(List.of(
                        new GitLsRefsResponse.DirectRef(
                                MAIN_ID,
                                "refs/heads/main\ninjected",
                                Optional.empty(),
                                Optional.empty()))),
                new GitLsRefsResponse(List.of(
                        new GitLsRefsResponse.DirectRef(
                                MAIN_ID,
                                "HEAD",
                                Optional.of("refs/heads/main branch"),
                                Optional.empty()))),
                new GitLsRefsResponse(List.of(
                        new GitLsRefsResponse.UnbornRef(
                                "",
                                "refs/heads/main"))),
                new GitLsRefsResponse(List.of(
                        new GitLsRefsResponse.UnbornRef(
                                "refs/heads/new",
                                "refs/heads/\u007fmain"))));

        try {
            for (GitLsRefsResponse response : malformed) {
                assertLsRefsSerializationFailed(output, response);
            }
            assertThat(outbound.writerIndex()).isZero();
        } finally {
            outbound.release();
        }
    }

    @Test
    void sendsProtocolV2FetchAcknowledgmentsForPresentHaves() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            assertThat(output.sendProtocolV2FetchAcknowledgments(List.of(
                    GitObjectId.of(MAIN_ID),
                    GitObjectId.of(TAG_ID))))
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
            assertThat(outbound.toString(StandardCharsets.US_ASCII))
                    .isEqualTo(
                            "0014acknowledgments\n"
                                    + "0031ACK " + MAIN_ID + "\n"
                                    + "0031ACK " + TAG_ID + "\n"
                                    + "0000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void sendsProtocolV2FetchNakWhenNoHavesAreAcknowledged() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            assertThat(output.sendProtocolV2FetchAcknowledgments(List.of()))
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
            assertThat(outbound.toString(StandardCharsets.US_ASCII))
                    .isEqualTo(
                            "0014acknowledgments\n"
                                    + "0008NAK\n"
                                    + "0000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void rejectsProtocolV2FetchAcknowledgmentsWhileOutputIsBusy() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        GitNativeClientOutput.ProtocolV2PackfileResponse response =
                output.beginProtocolV2Packfile(
                        producer("PACK".getBytes(StandardCharsets.US_ASCII)));

        try {
            assertThat(output.sendProtocolV2FetchAcknowledgments(List.of()))
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> assertThat(failed.message())
                                    .contains("already in progress"));
        } finally {
            response.close();
            outbound.release();
        }
    }

    @Test
    void sendsProtocolV2LsRefsRowAtExactPktLineLimit() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        String target = "x";
        int nameLength = 65_520
                - 4
                - "unborn ".length()
                - " symref-target:".length()
                - target.length()
                - "\n".length();
        String name = "r".repeat(nameLength);

        try {
            assertThat(output.sendLsRefs(new GitLsRefsResponse(List.of(
                    new GitLsRefsResponse.UnbornRef(name, target)))))
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
            assertThat(outbound.readableBytes())
                    .isEqualTo(65_520 + 4);
            assertThat(outbound.getCharSequence(
                    0,
                    4,
                    StandardCharsets.US_ASCII))
                    .hasToString("fff0");
            assertThat(outbound.getCharSequence(
                    65_520,
                    4,
                    StandardCharsets.US_ASCII))
                    .hasToString("0000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void rejectsProtocolV2LsRefsRowOneByteOverPktLineLimit() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        String target = "x";
        int nameLength = 65_520
                - 4
                - "unborn ".length()
                - " symref-target:".length()
                - target.length()
                - "\n".length()
                + 1;
        GitLsRefsResponse response = new GitLsRefsResponse(List.of(
                new GitLsRefsResponse.UnbornRef(
                        "r".repeat(nameLength),
                        target)));

        try {
            assertLsRefsSerializationFailed(output, response);
            assertThat(outbound.writerIndex()).isZero();
        } finally {
            outbound.release();
        }
    }

    @Test
    void sendsNakAsPktLine() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            assertThat(output.sendNak())
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
            assertThat(outbound.toString(StandardCharsets.US_ASCII))
                    .isEqualTo("0008NAK\n");
        } finally {
            outbound.release();
        }
    }

    @Test
    void sendsEveryAckStatusAsPktLine() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        Map<GitNativeClientOutput.AckStatus, String> expected =
                new LinkedHashMap<>();
        expected.put(
                GitNativeClientOutput.AckStatus.FINAL,
                "0031ACK " + MAIN_ID + "\n");
        expected.put(
                GitNativeClientOutput.AckStatus.CONTINUE,
                "003aACK " + MAIN_ID + " continue\n");
        expected.put(
                GitNativeClientOutput.AckStatus.COMMON,
                "0038ACK " + MAIN_ID + " common\n");
        expected.put(
                GitNativeClientOutput.AckStatus.READY,
                "0037ACK " + MAIN_ID + " ready\n");

        try {
            for (Map.Entry<GitNativeClientOutput.AckStatus, String> entry
                    : expected.entrySet()) {
                assertThat(output.sendAck(
                        GitObjectId.of(MAIN_ID),
                        entry.getKey()))
                        .isInstanceOf(
                                GitNativeClientOutput.SendResult.Completed.class);
                assertThat(outbound.toString(StandardCharsets.US_ASCII))
                        .isEqualTo(entry.getValue());
                outbound.clear();
            }
        } finally {
            outbound.release();
        }
    }

    @Test
    void sendsLegacyReceivePackStatusInsideSideBandData() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            assertThat(output.sendLegacyReceivePackStatus(
                    List.of(new GitNativeClientOutput.ReceiveCommandStatus(
                            "refs/heads/master",
                            true,
                            "")),
                    true))
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);

            assertThat(outbound.toString(StandardCharsets.US_ASCII))
                    .isEqualTo(
                            "0013\001000eunpack ok\n"
                                    + "001e\0010019ok refs/heads/master\n"
                                    + "0009\0010000"
                                    + "0000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void streamsLegacyReceivePackStatusWithoutMaterializingResponse() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = collectingOutput(outbound, sent);
        List<GitNativeClientOutput.ReceiveCommandStatus> statuses =
                new ArrayList<>();
        for (int index = 0; index < 4_000; index++) {
            statuses.add(new GitNativeClientOutput.ReceiveCommandStatus(
                    "refs/heads/branch-" + index,
                    true,
                    ""));
        }

        try {
            GitNativeClientOutput.SendResult result =
                    output.sendLegacyReceivePackStatus(statuses, true);

            assertThat(result)
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Streaming.class);
            ((GitNativeClientOutput.SendResult.Streaming) result)
                    .task()
                    .run();
            assertThat(sent).isNotEmpty();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            for (byte[] chunk : sent) {
                response.writeBytes(chunk);
            }
            assertThat(response.toString(StandardCharsets.US_ASCII))
                    .startsWith("0013\001000eunpack ok\n")
                    .contains("0020\001001bok refs/heads/branch-0\n")
                    .endsWith("0009\00100000000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void reportsInvalidLegacyReceivePackStatusThroughSendResult() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            assertThat(output.sendLegacyReceivePackStatus(
                    List.of(new GitNativeClientOutput.ReceiveCommandStatus(
                            "refs/heads/main",
                            false,
                            "has space")),
                    true))
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> assertThat(failed.message())
                                    .isEqualTo(
                                            "Failed to serialize legacy receive-pack status"));
        } finally {
            outbound.release();
        }
    }

    @Test
    void sendsLegacyPackOnEveryTypedSideBandChannel() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);

        try {
            for (GitNativeClientOutput.SideBandChannel channel
                    : GitNativeClientOutput.SideBandChannel.values()) {
                outbound.clear();
                List<byte[]> sent = new ArrayList<>();
                GitNativeClientOutput output =
                        collectingOutput(outbound, sent);

                complete(output.beginLegacySideBand64k(
                        producer(new byte[] {'P', 'A', 'C', 'K'}),
                        channel));
                ByteBuf response = Unpooled.wrappedBuffer(
                        sent.toArray(byte[][]::new));
                assertThat(response.readCharSequence(
                        8,
                        StandardCharsets.US_ASCII))
                        .hasToString("0008NAK\n");
                assertThat(response.readCharSequence(
                        4,
                        StandardCharsets.US_ASCII))
                        .hasToString("0009");
                assertThat(response.readByte())
                        .isEqualTo(channel.wireValue());
                byte[] pack = new byte[4];
                response.readBytes(pack);
                assertThat(pack)
                        .containsExactly('P', 'A', 'C', 'K');
                assertThat(response.readCharSequence(
                        4,
                        StandardCharsets.US_ASCII))
                        .hasToString("0000");
                response.release();
            }
        } finally {
            outbound.release();
        }
    }

    @Test
    void sendsLegacyPackWithoutSideBandFraming() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = collectingOutput(outbound, sent);

        try {
            complete(output.beginLegacyPack(
                    producer(new byte[] {'P', 'A', 'C', 'K'})));
            ByteBuf response = Unpooled.wrappedBuffer(
                    sent.toArray(byte[][]::new));
            assertThat(response.readCharSequence(
                    8,
                    StandardCharsets.US_ASCII))
                    .hasToString("0008NAK\n");
            byte[] pack = new byte[4];
            response.readBytes(pack);
            assertThat(pack)
                    .containsExactly('P', 'A', 'C', 'K');
            assertThat(response.isReadable()).isFalse();
            response.release();
        } finally {
            outbound.release();
        }
    }

    @Test
    void fragmentsLegacySideBand64kPackAtPktLineLimit() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = collectingOutput(outbound, sent);
        byte[] pack = new byte[65_516];
        pack[0] = 'P';
        pack[1] = 'A';
        pack[2] = 'C';
        pack[3] = 'K';

        GitNativeClientOutput.LegacySideBandResponse sideBandResponse =
                output.beginLegacySideBand64k(
                        producer(pack),
                        GitNativeClientOutput.SideBandChannel.DATA);

        try {
            int taskCount = 0;
            while (true) {
                GitNativeClientOutput.SendResult result =
                        sideBandResponse.advance();
                if (result instanceof
                        GitNativeClientOutput.SendResult.Completed) {
                    break;
                }
                GitNativeClientOutput.SendResult.Streaming streaming =
                        (GitNativeClientOutput.SendResult.Streaming) result;
                int submissionsBefore = sent.size();
                streaming.task().run();
                taskCount++;
                assertThat(sent)
                        .hasSize(submissionsBefore + 1);
            }
            assertThat(taskCount).isGreaterThan(1);
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            for (byte[] chunk : sent) {
                response.writeBytes(chunk);
            }
            byte[] bytes = response.toByteArray();
            assertThat(new String(
                    bytes,
                    0,
                    8,
                    StandardCharsets.US_ASCII))
                    .isEqualTo("0008NAK\n");
            assertThat(new String(
                    bytes,
                    8,
                    4,
                    StandardCharsets.US_ASCII))
                    .isEqualTo("fff0");
            assertThat(bytes[12]).isEqualTo((byte) 1);
            assertThat(new String(
                    bytes,
                    65_528,
                    4,
                    StandardCharsets.US_ASCII))
                    .isEqualTo("0006");
            assertThat(bytes[65_532]).isEqualTo((byte) 1);
            assertThat(new String(
                    bytes,
                    bytes.length - 4,
                    4,
                    StandardCharsets.US_ASCII))
                    .isEqualTo("0000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void interleavesOrderedProgressAndErrorBetweenPackData() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = collectingOutput(outbound, sent);
        byte[] pack = new byte[100_000];
        java.util.Arrays.fill(pack, (byte) 'D');
        GitNativeClientOutput.LegacySideBandResponse response =
                output.beginLegacySideBand64k(
                        producer(pack),
                        GitNativeClientOutput.SideBandChannel.DATA);

        try {
            GitNativeClientOutput.SendResult.Streaming first =
                    (GitNativeClientOutput.SendResult.Streaming)
                            response.advance();
            first.task().run();

            ByteBuf progress = Unpooled.copiedBuffer(
                    "counting\n",
                    StandardCharsets.UTF_8);
            ByteBuf error = Unpooled.copiedBuffer(
                    "recoverable warning\n",
                    StandardCharsets.UTF_8);
            try {
                assertThat(response.progress(progress))
                        .isInstanceOf(
                                GitNativeClientOutput.SendResult.Completed.class);
                assertThat(response.error(error))
                        .isInstanceOf(
                                GitNativeClientOutput.SendResult.Completed.class);
            } finally {
                progress.release();
                error.release();
            }

            complete(response);

            SideBandTranscript transcript = transcript(sent);
            assertThat(transcript.channels())
                    .containsExactly(1, 1, 2, 3, 1);
            assertThat(transcript.data()).containsExactly(pack);
            assertThat(new String(
                    transcript.progress(),
                    StandardCharsets.UTF_8))
                    .isEqualTo("counting\n");
            assertThat(new String(
                    transcript.errors(),
                    StandardCharsets.UTF_8))
                    .isEqualTo("recoverable warning\n");
        } finally {
            response.close();
            outbound.release();
        }
    }

    @Test
    void copiesFragmentsAndOrdersQueuedSideBandMessages() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = collectingOutput(outbound, sent);
        byte[] pack = {'P', 'A', 'C', 'K'};
        byte[] largeProgress = new byte[70_000];
        java.util.Arrays.fill(largeProgress, (byte) 'p');
        GitNativeClientOutput.LegacySideBandResponse response =
                output.beginLegacySideBand64k(
                        producer(pack),
                        GitNativeClientOutput.SideBandChannel.DATA);
        ByteBuf firstProgress = Unpooled.wrappedBuffer(
                largeProgress.clone());
        ByteBuf error = Unpooled.copiedBuffer(
                "warning\n",
                StandardCharsets.UTF_8);
        ByteBuf finalProgress = Unpooled.copiedBuffer(
                "done\n",
                StandardCharsets.UTF_8);

        try {
            assertThat(response.progress(firstProgress))
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
            assertThat(response.error(error))
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
            assertThat(response.progress(finalProgress))
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
            firstProgress.setByte(0, 'x');
            error.setByte(0, 'x');
            finalProgress.setByte(0, 'x');

            complete(response);

            SideBandTranscript transcript = transcript(sent);
            assertThat(transcript.channels())
                    .containsExactly(2, 2, 2, 3, 2, 1);
            ByteArrayOutputStream expectedProgress =
                    new ByteArrayOutputStream();
            expectedProgress.writeBytes(largeProgress);
            expectedProgress.writeBytes(
                    "done\n".getBytes(StandardCharsets.UTF_8));
            assertThat(transcript.progress())
                    .containsExactly(expectedProgress.toByteArray());
            assertThat(new String(
                    transcript.errors(),
                    StandardCharsets.UTF_8))
                    .isEqualTo("warning\n");
            assertThat(transcript.data()).containsExactly(pack);
        } finally {
            firstProgress.release();
            error.release();
            finalProgress.release();
            response.close();
            outbound.release();
        }
    }

    @Test
    void drainsMessageAcceptedWhileProducerCompletesBeforeFlush() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = collectingOutput(outbound, sent);
        GitNativeClientOutput.LegacySideBandResponse[] response =
                new GitNativeClientOutput.LegacySideBandResponse[1];
        NativePackProducer producer = new NativePackProducer() {
            @Override
            public Result produce(ByteBuf destination) {
                destination.writeBytes(
                        new byte[] {'P', 'A', 'C', 'K'});
                ByteBuf progress = Unpooled.copiedBuffer(
                        "done\n",
                        StandardCharsets.UTF_8);
                try {
                    assertThat(response[0].progress(progress))
                            .isInstanceOf(
                                    GitNativeClientOutput.SendResult.Completed.class);
                } finally {
                    progress.release();
                }
                return Result.COMPLETED;
            }

            @Override
            public void close() {
            }
        };
        response[0] = output.beginLegacySideBand64k(
                producer,
                GitNativeClientOutput.SideBandChannel.DATA);

        try {
            complete(response[0]);

            SideBandTranscript transcript = transcript(sent);
            assertThat(transcript.channels()).containsExactly(1, 2);
            assertThat(transcript.data())
                    .containsExactly('P', 'A', 'C', 'K');
            assertThat(new String(
                    transcript.progress(),
                    StandardCharsets.UTF_8))
                    .isEqualTo("done\n");
        } finally {
            response[0].close();
            outbound.release();
        }
    }

    @Test
    void rejectsOtherOutputWhileSideBandResponseIsActive() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        GitNativeClientOutput.LegacySideBandResponse response =
                output.beginLegacySideBand64k(
                        producer(new byte[] {'P', 'A', 'C', 'K'}),
                        GitNativeClientOutput.SideBandChannel.DATA);

        try {
            assertThat(output.sendNak())
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> assertThat(failed.message())
                                    .contains("already in progress"));
        } finally {
            response.close();
            outbound.release();
        }
    }

    @Test
    void reportsBeginResponseFailureWhenAnotherPackResponseIsActive() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        GitNativeClientOutput.LegacySideBandResponse active =
                output.beginLegacySideBand64k(
                        producer(new byte[] {'P', 'A', 'C', 'K'}),
                        GitNativeClientOutput.SideBandChannel.DATA);
        AtomicBoolean sideBandProducerClosed = new AtomicBoolean();
        AtomicBoolean legacyPackProducerClosed = new AtomicBoolean();
        AtomicBoolean protocolV2ProducerClosed = new AtomicBoolean();

        try {
            GitNativeClientOutput.LegacySideBandResponse sideBand =
                    output.beginLegacySideBand64k(
                            producer(sideBandProducerClosed),
                            GitNativeClientOutput.SideBandChannel.DATA);
            GitNativeClientOutput.LegacyPackResponse legacyPack =
                    output.beginLegacyPack(
                            producer(legacyPackProducerClosed));
            GitNativeClientOutput.ProtocolV2PackfileResponse protocolV2 =
                    output.beginProtocolV2Packfile(
                            producer(protocolV2ProducerClosed));

            assertThat(sideBand.advance())
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> assertThat(failed.message())
                                    .contains("already in progress"));
            assertThat(legacyPack.advance())
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> assertThat(failed.message())
                                    .contains("already in progress"));
            assertThat(protocolV2.advance())
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> assertThat(failed.message())
                                    .contains("already in progress"));
            assertThat(sideBandProducerClosed).isTrue();
            assertThat(legacyPackProducerClosed).isTrue();
            assertThat(protocolV2ProducerClosed).isTrue();
        } finally {
            active.close();
            outbound.release();
        }
    }

    @Test
    void rollsBackStagedResponseWhenPackProductionFails() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        outbound.writeCharSequence(
                "prefix",
                StandardCharsets.US_ASCII);
        int initialWriterIndex = outbound.writerIndex();
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        TrackingCopyByteBuf message = new TrackingCopyByteBuf(
                Unpooled.copiedBuffer(
                        "queued before failure\n",
                        StandardCharsets.UTF_8));
        GitNativeClientOutput.LegacySideBandResponse[] response =
                new GitNativeClientOutput.LegacySideBandResponse[1];
        NativePackProducer producer = new NativePackProducer() {
            @Override
            public Result produce(ByteBuf destination) {
                assertThat(response[0].progress(message))
                        .isInstanceOf(
                                GitNativeClientOutput.SendResult.Completed.class);
                destination.writeByte('x');
                throw new IllegalStateException("production failed");
            }

            @Override
            public void close() {
            }
        };
        response[0] = output.beginLegacySideBand64k(
                        producer,
                        GitNativeClientOutput.SideBandChannel.DATA);

        try {
            assertThat(response[0].advance())
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> assertThat(failed.cause())
                                    .hasMessage("production failed"));
            assertThat(outbound.writerIndex())
                    .isEqualTo(initialWriterIndex);
            assertThat(outbound.toString(
                    StandardCharsets.US_ASCII))
                    .isEqualTo("prefix");
            assertThat(message.createdCopy().refCnt()).isZero();
        } finally {
            message.release();
            response[0].close();
            outbound.release();
        }
    }

    @Test
    void reportsSideBandDeliveryFailureAndClosesResponse() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        AtomicBoolean producerClosed = new AtomicBoolean();
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                ignored -> {
                    throw new IllegalStateException("send failed");
                });
        NativePackProducer producer = new NativePackProducer() {
            private boolean produced;

            @Override
            public Result produce(ByteBuf destination) {
                if (!produced) {
                    destination.writeBytes(
                            new byte[] {'P', 'A', 'C', 'K'});
                    produced = true;
                }
                return Result.COMPLETED;
            }

            @Override
            public void close() {
                producerClosed.set(true);
            }
        };
        GitNativeClientOutput.LegacySideBandResponse response =
                output.beginLegacySideBand64k(
                        producer,
                        GitNativeClientOutput.SideBandChannel.DATA);

        try {
            GitNativeClientOutput.SendResult.Streaming streaming =
                    (GitNativeClientOutput.SendResult.Streaming)
                            response.advance();
            streaming.task().run();

            assertThat(response.advance())
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> {
                                assertThat(failed.message())
                                        .contains("deliver");
                                assertThat(failed.cause())
                                        .hasMessage("send failed");
                            });
            assertThat(producerClosed).isTrue();
        } finally {
            response.close();
            outbound.release();
        }
    }

    @Test
    void reportsInvalidSideBandMessageThroughSendResult() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        GitNativeClientOutput.LegacySideBandResponse response =
                output.beginLegacySideBand64k(
                        producer(new byte[] {'P', 'A', 'C', 'K'}),
                        GitNativeClientOutput.SideBandChannel.DATA);

        try {
            assertThat(response.progress(null))
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> assertThat(failed.cause())
                                    .isInstanceOf(
                                            NullPointerException.class));
        } finally {
            response.close();
            outbound.release();
        }
    }

    @Test
    void releasesQueuedMessageCopyWhenResponseCloses() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        GitNativeClientOutput.LegacySideBandResponse response =
                output.beginLegacySideBand64k(
                        producer(new byte[] {'P', 'A', 'C', 'K'}),
                        GitNativeClientOutput.SideBandChannel.DATA);
        TrackingCopyByteBuf message = new TrackingCopyByteBuf(
                Unpooled.copiedBuffer(
                        "queued\n",
                        StandardCharsets.UTF_8));

        try {
            assertThat(response.progress(message))
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
            assertThat(message.createdCopy().refCnt()).isOne();

            response.close();

            assertThat(message.createdCopy().refCnt()).isZero();
        } finally {
            message.release();
            response.close();
            outbound.release();
        }
    }

    private static void complete(
            GitNativeClientOutput.LegacySideBandResponse response) {
        while (true) {
            GitNativeClientOutput.SendResult result =
                    response.advance();
            if (result
                    instanceof GitNativeClientOutput.SendResult.Completed) {
                return;
            }
            assertThat(result)
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Streaming.class);
            ((GitNativeClientOutput.SendResult.Streaming) result)
                    .task()
                    .run();
        }
    }

    private static void complete(
            GitNativeClientOutput.LegacyPackResponse response) {
        while (true) {
            GitNativeClientOutput.SendResult result =
                    response.advance();
            if (result
                    instanceof GitNativeClientOutput.SendResult.Completed) {
                return;
            }
            assertThat(result)
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Streaming.class);
            ((GitNativeClientOutput.SendResult.Streaming) result)
                    .task()
                    .run();
        }
    }

    private static NativePackProducer producer(byte[] bytes) {
        return new NativePackProducer() {
            private int offset;

            @Override
            public Result produce(ByteBuf destination) {
                int length = Math.min(
                        destination.writableBytes(),
                        bytes.length - offset);
                destination.writeBytes(bytes, offset, length);
                offset += length;
                return offset == bytes.length
                        ? Result.COMPLETED
                        : Result.MORE;
            }

            @Override
            public void close() {
            }
        };
    }

    private static NativePackProducer producer(
            AtomicBoolean closed) {
        return new NativePackProducer() {
            @Override
            public Result produce(ByteBuf destination) {
                destination.writeByte('x');
                return Result.COMPLETED;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }

    @Test
    void streamsAckAfterExistingPartiallyFilledOutput() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        int initialWriterIndex = outbound.capacity() - 10;
        outbound.writerIndex(initialWriterIndex);
        outbound.setByte(initialWriterIndex - 1, 'x');
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = collectingOutput(outbound, sent);

        GitNativeClientOutput.SendResult.Streaming streaming =
                (GitNativeClientOutput.SendResult.Streaming)
                        output.sendAck(
                                GitObjectId.of(MAIN_ID),
                                GitNativeClientOutput.AckStatus.READY);

        try {
            assertThat(output.sendNak())
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> assertThat(failed.message())
                                    .contains("already in progress"));

            streaming.task().run();

            ByteArrayOutputStream allSent = new ByteArrayOutputStream();
            for (byte[] chunk : sent) {
                allSent.writeBytes(chunk);
            }
            byte[] bytes = allSent.toByteArray();
            assertThat(bytes[initialWriterIndex - 1])
                    .isEqualTo((byte) 'x');
            assertThat(new String(
                    bytes,
                    initialWriterIndex,
                    bytes.length - initialWriterIndex,
                    StandardCharsets.US_ASCII))
                    .isEqualTo(
                            "0037ACK " + MAIN_ID + " ready\n");
            assertThat(output.sendNak())
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
        } finally {
            outbound.release();
        }
    }

    @Test
    void returnsFailedWhenAckCannotBeSerialized() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            assertThat(output.sendAck(
                    GitObjectId.of("x".repeat(65 * 1024)),
                    GitNativeClientOutput.AckStatus.FINAL))
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> {
                                assertThat(failed.message())
                                        .contains("serialize");
                                assertThat(failed.cause())
                                        .isInstanceOf(
                                                IllegalArgumentException.class);
                            });
            assertThat(outbound.writerIndex()).isZero();
        } finally {
            outbound.release();
        }
    }

    @Test
    void streamsNakWhenOutputIsAlreadyFull() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        outbound.writerIndex(outbound.capacity());
        outbound.setByte(outbound.writerIndex() - 1, 'x');
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = collectingOutput(outbound, sent);

        GitNativeClientOutput.SendResult.Streaming streaming =
                (GitNativeClientOutput.SendResult.Streaming)
                        output.sendNak();
        streaming.task().run();

        try {
            assertThat(sent).hasSize(2);
            assertThat(sent.getFirst()).hasSize(outbound.capacity());
            assertThat(sent.getFirst()[outbound.capacity() - 1])
                    .isEqualTo((byte) 'x');
            assertThat(new String(
                    sent.getLast(),
                    StandardCharsets.US_ASCII))
                    .isEqualTo("0008NAK\n");
        } finally {
            outbound.release();
        }
    }

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

        assertThat(output.sendAdvertisement(advertisement))
                .isInstanceOf(
                        GitNativeClientOutput.SendResult.Completed.class);

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
            assertThat(output.sendAdvertisement(advertisement))
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Streaming.class);
            assertThat(outbound.writerIndex()).isGreaterThan(writerIndex);
        } finally {
            outbound.release();
        }
    }

    @Test
    void streamingTaskSendsBufferedPrefixAndRemainingAdvertisement() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        outbound.writerIndex(outbound.capacity() - 1);
        outbound.setByte(outbound.writerIndex() - 1, 'x');
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                chunk -> {
                    try {
                        byte[] bytes = new byte[chunk.readableBytes()];
                        chunk.readBytes(bytes);
                        sent.add(bytes);
                    } finally {
                        chunk.release();
                    }
                });
        GitV1Advertisement advertisement = new GitV1Advertisement(
                List.of(),
                List.of(GitAdvertisedRef.direct(
                        MAIN_ID,
                        "refs/heads/main")));

        GitNativeClientOutput.SendResult.Streaming streaming =
                (GitNativeClientOutput.SendResult.Streaming)
                        output.sendAdvertisement(advertisement);
        streaming.task().run();

        try {
            assertThat(sent).hasSize(2);
            ByteArrayOutputStream allSent = new ByteArrayOutputStream();
            for (byte[] chunk : sent) {
                allSent.writeBytes(chunk);
            }
            byte[] bytes = allSent.toByteArray();
            assertThat(bytes[outbound.capacity() - 3]).isZero();
            assertThat(bytes[outbound.capacity() - 2]).isEqualTo((byte) 'x');
            assertThat(new String(
                    bytes,
                    outbound.capacity() - 1,
                    bytes.length - outbound.capacity() + 1,
                    StandardCharsets.UTF_8))
                    .isEqualTo(
                            "003e" + MAIN_ID
                                    + " refs/heads/main\0\n0000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void rejectsConcurrentOperationUntilStreamingTaskCompletes() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        outbound.writerIndex(outbound.capacity() - 1);
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                ByteBuf::release);
        GitV1Advertisement advertisement = new GitV1Advertisement(
                List.of(),
                List.of(GitAdvertisedRef.direct(
                        MAIN_ID,
                        "refs/heads/main")));

        GitNativeClientOutput.SendResult.Streaming streaming =
                (GitNativeClientOutput.SendResult.Streaming)
                        output.sendAdvertisement(advertisement);
        try {
            assertThat(output.sendAdvertisement(advertisement))
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> assertThat(failed.message())
                                    .contains("already in progress"));

            streaming.task().run();

            assertThat(output.sendAdvertisement(advertisement))
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
        } finally {
            outbound.release();
        }
    }

    @Test
    void submittedChunksRemainStableAfterOutputBufferReuse() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        outbound.writerIndex(outbound.capacity() - 1);
        outbound.setByte(outbound.writerIndex() - 1, 'x');
        List<ByteBuf> submitted = new ArrayList<>();
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                submitted::add);
        GitV1Advertisement advertisement = new GitV1Advertisement(
                List.of(),
                List.of(GitAdvertisedRef.direct(
                        MAIN_ID,
                        "refs/heads/main")));

        GitNativeClientOutput.SendResult.Streaming streaming =
                (GitNativeClientOutput.SendResult.Streaming)
                        output.sendAdvertisement(advertisement);
        streaming.task().run();

        try {
            assertThat(submitted).hasSize(2);
            assertThat(submitted.getFirst().getByte(
                    submitted.getFirst().writerIndex() - 2))
                    .isEqualTo((byte) 'x');
            assertThat(submitted.getFirst().getByte(
                    submitted.getFirst().writerIndex() - 1))
                    .isEqualTo((byte) '0');
            assertThat(submitted.getLast().toString(
                    StandardCharsets.UTF_8))
                    .endsWith("0000");
        } finally {
            for (ByteBuf chunk : submitted) {
                chunk.release();
            }
            outbound.release();
        }
    }

    @Test
    void streamsAdvertisementAcrossMultipleFullBuffers() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                chunk -> {
                    try {
                        byte[] bytes = new byte[chunk.readableBytes()];
                        chunk.readBytes(bytes);
                        sent.add(bytes);
                    } finally {
                        chunk.release();
                    }
                });
        List<GitAdvertisedRef> refs = new ArrayList<>();
        for (int index = 0; index < 3_000; index++) {
            refs.add(GitAdvertisedRef.direct(
                    "%040x".formatted(index + 1),
                    "refs/heads/branch-%04d".formatted(index)));
        }
        GitV1Advertisement advertisement =
                new GitV1Advertisement(List.of(), refs);

        GitNativeClientOutput.SendResult.Streaming streaming =
                (GitNativeClientOutput.SendResult.Streaming)
                        output.sendAdvertisement(advertisement);
        streaming.task().run();

        try {
            assertThat(sent).hasSizeGreaterThan(2);
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            for (byte[] chunk : sent) {
                actual.writeBytes(chunk);
            }
            assertThat(actual.toByteArray())
                    .containsExactly(expectedAdvertisement(refs));
        } finally {
            outbound.release();
        }
    }

    @Test
    void streamingTaskReportsSendFailureOnResumptionAndReleasesOperation() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        outbound.writerIndex(outbound.capacity());
        IllegalStateException failure =
                new IllegalStateException("send failed");
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                ignored -> {
                    throw failure;
                });
        GitV1Advertisement advertisement = new GitV1Advertisement(
                List.of(),
                List.of(GitAdvertisedRef.direct(
                        MAIN_ID,
                        "refs/heads/main")));
        Continuation<ByteBuf> next =
                input -> ContinuationFlow.await();

        GitNativeClientOutput.SendResult.Streaming streaming =
                (GitNativeClientOutput.SendResult.Streaming)
                        output.sendAdvertisement(advertisement);
        ContinuationFlow.TransitionAndYield<ByteBuf> yielded =
                (ContinuationFlow.TransitionAndYield<ByteBuf>)
                        streaming.transitionTo(next);

        try {
            assertThatCode(yielded.task()::run)
                    .doesNotThrowAnyException();
            assertThat(yielded.next().process(Unpooled.EMPTY_BUFFER))
                    .isInstanceOfSatisfying(
                            ContinuationFlow.Transition.class,
                            transition -> assertThat(transition.next())
                                    .isInstanceOfSatisfying(
                                            Continuation.CompletedError.class,
                                            error -> {
                                                assertThat(error.message())
                                                        .isEqualTo(
                                                                "Failed to deliver"
                                                                        + " serialized client output");
                                                assertThat(error.throwable())
                                                        .isSameAs(failure);
                                            }));
            assertThat(output.sendAdvertisement(advertisement))
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> assertThat(failed.cause())
                                    .isSameAs(failure));
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
                            output,
                            new InMemoryNativeGitRepositoryProvider(),
                            GitNativeRepositoryAccessHook.ALLOW_ALL);

            assertThat(context.clientOutput).isSameAs(output);
        } finally {
            outbound.release();
        }
    }

    private static void assertLsRefsSerializationFailed(
            GitNativeClientOutput output,
            GitLsRefsResponse response) {
        assertThat(output.sendLsRefs(response))
                .isInstanceOfSatisfying(
                        GitNativeClientOutput.SendResult.Failed.class,
                        failed -> {
                            assertThat(failed.message()).isEqualTo(
                                    "Failed to serialize protocol v2"
                                            + " ls-refs response");
                            assertThat(failed.cause())
                                    .isInstanceOf(
                                            IllegalArgumentException.class);
                        });
    }

    private static byte[] expectedAdvertisement(
            List<GitAdvertisedRef> refs) {
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (int index = 0; index < refs.size(); index++) {
            GitAdvertisedRef ref = refs.get(index);
            String suffix = index == 0 ? "\0\n" : "\n";
            byte[] payload = (ref.objectId()
                    + " "
                    + ref.name()
                    + suffix).getBytes(StandardCharsets.UTF_8);
            expected.writeBytes(
                    "%04x".formatted(payload.length + 4)
                            .getBytes(StandardCharsets.US_ASCII));
            expected.writeBytes(payload);
        }
        expected.writeBytes(
                "0000".getBytes(StandardCharsets.US_ASCII));
        return expected.toByteArray();
    }

    private static SideBandTranscript transcript(
            List<byte[]> sent) {
        ByteBuf wire = Unpooled.wrappedBuffer(
                sent.toArray(byte[][]::new));
        try {
            assertThat(wire.readCharSequence(
                    8,
                    StandardCharsets.US_ASCII))
                    .hasToString("0008NAK\n");
            List<Integer> channels = new ArrayList<>();
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            ByteArrayOutputStream progress = new ByteArrayOutputStream();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            while (true) {
                int length = Integer.parseInt(
                        wire.readCharSequence(
                                4,
                                StandardCharsets.US_ASCII).toString(),
                        16);
                if (length == 0) {
                    break;
                }
                int channel = wire.readUnsignedByte();
                byte[] payload = new byte[length - 5];
                wire.readBytes(payload);
                channels.add(channel);
                switch (channel) {
                    case 1 -> data.writeBytes(payload);
                    case 2 -> progress.writeBytes(payload);
                    case 3 -> errors.writeBytes(payload);
                    default -> throw new AssertionError(
                            "Unexpected side-band channel " + channel);
                }
            }
            assertThat(wire.isReadable()).isFalse();
            return new SideBandTranscript(
                    List.copyOf(channels),
                    data.toByteArray(),
                    progress.toByteArray(),
                    errors.toByteArray());
        } finally {
            wire.release();
        }
    }

    private record SideBandTranscript(
            List<Integer> channels,
            byte[] data,
            byte[] progress,
            byte[] errors) {
    }

    private static final class TrackingCopyByteBuf
            extends WrappedByteBuf {
        private ByteBuf createdCopy;

        private TrackingCopyByteBuf(ByteBuf buffer) {
            super(buffer);
        }

        @Override
        public ByteBuf copy(int index, int length) {
            createdCopy = super.copy(index, length);
            return createdCopy;
        }

        private ByteBuf createdCopy() {
            return createdCopy;
        }
    }

    private static GitNativeClientOutput collectingOutput(
            ByteBuf outbound,
            List<byte[]> sent) {
        return new GitNativeClientOutput(
                outbound,
                chunk -> {
                    try {
                        byte[] bytes = new byte[chunk.readableBytes()];
                        chunk.readBytes(bytes);
                        sent.add(bytes);
                    } finally {
                        chunk.release();
                    }
                });
    }
}
