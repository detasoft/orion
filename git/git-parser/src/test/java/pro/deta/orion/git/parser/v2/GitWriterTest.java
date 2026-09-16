package pro.deta.orion.git.parser.v2;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.fetch.NegotiationResponse;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pkt.SideBand;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData.ProtocolVersion;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.fetch.NegotiationResponse.Control.NAK;
import static pro.deta.orion.git.parser.v2.fetch.NegotiationResponse.Control.READY;
import static pro.deta.orion.git.parser.v2.fetch.NegotiationResponse.Status.COMMON;
import static pro.deta.orion.git.parser.v2.fetch.NegotiationResponse.Status.CONTINUE;
import static pro.deta.orion.git.parser.v2.fetch.NegotiationResponse.Status.PLAIN;

class GitWriterTest {
    private static final ObjectId ID = new ObjectId("ab".repeat(20));
    private static final ObjectId OTHER = new ObjectId("cd".repeat(20));

    @Test
    void legacyWritesOrderedAckSuffixesWithoutAddingRoundMarkers() throws Exception {
        for (ProtocolVersion version : new ProtocolVersion[]{ProtocolVersion.V0, ProtocolVersion.V1}) {
            var bytes = new ByteArrayOutputStream();
            var writer = new GitWriter(new OutputStreamBufferedByteOutput(bytes));
            writer.writeNegotiationRound(List.of(new NegotiationResponse.Ack(ID, CONTINUE),
                    new NegotiationResponse.Ack(ID, COMMON), new NegotiationResponse.Ack(ID, PLAIN),
                    new NegotiationResponse.Ack(ID, NegotiationResponse.Status.READY), NAK),
                    version, SideBand.NONE);
            assertThat(bytes.toString(StandardCharsets.US_ASCII)).isEqualTo(
                    "003aACK " + ID + " continue\n0038ACK " + ID + " common\n0031ACK " + ID
                            + "\n0037ACK " + ID + " ready\n0008NAK\n");
        }
    }

    @Test
    void v2NakSectionEndsWithFlushPacket() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var writer = new GitWriter(new OutputStreamBufferedByteOutput(bytes));
        writer.writeNegotiationRound(List.of(NAK), ProtocolVersion.V2, SideBand.NONE);
        assertThat(bytes.toString(StandardCharsets.US_ASCII)).isEqualTo("0014acknowledgments\n0008NAK\n0000");
    }

    @Test
    void v2AcknowledgmentsPreserveOrderAndEndWithFlushWithoutReady() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var writer = new GitWriter(new OutputStreamBufferedByteOutput(bytes));
        writer.writeNegotiationRound(List.of(new NegotiationResponse.Ack(ID, PLAIN),
                new NegotiationResponse.Ack(OTHER, PLAIN)), ProtocolVersion.V2, SideBand.NONE);
        assertThat(bytes.toString(StandardCharsets.US_ASCII)).isEqualTo(
                "0014acknowledgments\n0031ACK " + ID + "\n0031ACK " + OTHER + "\n0000");
    }

    @Test
    void v2ReadyDelimitsTheFollowingSectionWithOptionalSideband() throws Exception {
        for (SideBand channel : new SideBand[]{SideBand.NONE, SideBand.DATA}) {
            var bytes = new ByteArrayOutputStream();
            var writer = new GitWriter(new OutputStreamBufferedByteOutput(bytes));
            writer.writeNegotiationRound(List.of(new NegotiationResponse.Ack(ID, PLAIN), READY),
                    ProtocolVersion.V2, channel);
            String expected = channel == SideBand.NONE
                    ? "0014acknowledgments\n0031ACK " + ID + "\n000aready\n0001"
                    : "0015\u0001acknowledgments\n0032\u0001ACK " + ID + "\n000b\u0001ready\n0001";
            assertThat(bytes.toString(StandardCharsets.US_ASCII)).isEqualTo(expected);
        }
    }

    @Test
    void emptyRepliesWriteNothingInAnyVersion() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var writer = new GitWriter(new OutputStreamBufferedByteOutput(bytes));
        for (ProtocolVersion version : ProtocolVersion.values()) {
            writer.writeNegotiationRound(List.of(), version, SideBand.NONE);
        }
        assertThat(bytes.size()).isZero();
    }

    @Test
    void transportFlushIsExplicitAndDoesNotEncodeAnotherPacketOrCloseOutput() throws Exception {
        var bytes = new ByteArrayOutputStream() {
            private int flushes;

            @Override
            public void flush() {
                flushes++;
            }

            @Override
            public void close() {
                throw new AssertionError("Writer borrows output");
            }
        };
        var writer = new GitWriter(new OutputStreamBufferedByteOutput(bytes));
        writer.writeNegotiationRound(List.of(NAK), ProtocolVersion.V2, SideBand.DATA);
        assertThat(bytes.flushes).isZero();
        writer.flush();
        assertThat(bytes.flushes).isEqualTo(1);
        assertThat(bytes.toString(StandardCharsets.US_ASCII))
                .isEqualTo("0015\u0001acknowledgments\n0009\u0001NAK\n0000");
    }

    @Test
    void propagatesWriteAndFlushFailures() {
        var failure = new IOException("Transport failed");
        var writer = new GitWriter(new OutputStreamBufferedByteOutput(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw failure;
            }

            @Override
            public void flush() throws IOException {
                throw failure;
            }
        }));
        assertThatThrownBy(() -> writer.writeNegotiationRound(List.of(NAK), ProtocolVersion.V0, SideBand.NONE))
                .isSameAs(failure);
        assertThatThrownBy(writer::flush).isSameAs(failure);
    }
}
