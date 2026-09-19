package pro.deta.orion.git.parser.v2.proto;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.fetch.NegotiationResponse;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pkt.SideBand;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

class GitProtocolWriterTest {
    private static final ObjectId ID = new ObjectId("ab".repeat(20));
    private static final ObjectId OTHER = new ObjectId("cd".repeat(20));

    @Test
    void legacyWritesOrderedAckSuffixesWithoutAddingRoundMarkers() throws Exception {
        for (GitProtocolVersion version : new GitProtocolVersion[]{
                GitProtocolVersion.V0, GitProtocolVersion.V1}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            GitProtocolContext.Writer writer = writer(bytes, version);
            writer.writeNegotiationRound(List.of(new NegotiationResponse.Ack(ID, CONTINUE),
                    new NegotiationResponse.Ack(ID, COMMON), new NegotiationResponse.Ack(ID, PLAIN),
                    new NegotiationResponse.Ack(ID, NegotiationResponse.Status.READY), NAK),
                    SideBand.NONE);
            assertThat(bytes.toString(StandardCharsets.US_ASCII)).isEqualTo(
                    "003aACK " + ID + " continue\n0038ACK " + ID + " common\n0031ACK " + ID
                            + "\n0037ACK " + ID + " ready\n0008NAK\n");
        }
    }

    @Test
    void v2NakSectionEndsWithFlushPacket() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GitProtocolContext.Writer writer = writer(bytes, GitProtocolVersion.V2);
        writer.writeNegotiationRound(List.of(NAK), SideBand.NONE);
        assertThat(bytes.toString(StandardCharsets.US_ASCII)).isEqualTo("0014acknowledgments\n0008NAK\n0000");
    }

    @Test
    void v2AcknowledgmentsPreserveOrderAndEndWithFlushWithoutReady() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GitProtocolContext.Writer writer = writer(bytes, GitProtocolVersion.V2);
        writer.writeNegotiationRound(List.of(new NegotiationResponse.Ack(ID, PLAIN),
                new NegotiationResponse.Ack(OTHER, PLAIN)), SideBand.NONE);
        assertThat(bytes.toString(StandardCharsets.US_ASCII)).isEqualTo(
                "0014acknowledgments\n0031ACK " + ID + "\n0031ACK " + OTHER + "\n0000");
    }

    @Test
    void v2ReadyDelimitsTheFollowingSectionWithOptionalSideband() throws Exception {
        for (SideBand channel : new SideBand[]{SideBand.NONE, SideBand.DATA}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            GitProtocolContext.Writer writer = writer(bytes, GitProtocolVersion.V2);
            writer.writeNegotiationRound(List.of(new NegotiationResponse.Ack(ID, PLAIN), READY),
                    channel);
            String expected = channel == SideBand.NONE
                    ? "0014acknowledgments\n0031ACK " + ID + "\n000aready\n0001"
                    : "0015\u0001acknowledgments\n0032\u0001ACK " + ID + "\n000b\u0001ready\n0001";
            assertThat(bytes.toString(StandardCharsets.US_ASCII)).isEqualTo(expected);
        }
    }

    @Test
    void emptyRepliesWriteNothingInAnyVersion() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (GitProtocolVersion version : GitProtocolVersion.values()) {
            writer(bytes, version).writeNegotiationRound(List.of(), SideBand.NONE);
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
        GitProtocolContext.Writer writer = writer(bytes, GitProtocolVersion.V2);
        writer.writeNegotiationRound(List.of(NAK), SideBand.DATA);
        assertThat(bytes.flushes).isZero();
        writer.flush();
        assertThat(bytes.flushes).isEqualTo(1);
        assertThat(bytes.toString(StandardCharsets.US_ASCII))
                .isEqualTo("0015\u0001acknowledgments\n0009\u0001NAK\n0000");
    }

    @Test
    void propagatesWriteAndFlushFailures() {
        IOException failure = new IOException("Transport failed");
        GitProtocolContext.Writer writer = writer(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw failure;
            }

            @Override
            public void flush() throws IOException {
                throw failure;
            }
        }, GitProtocolVersion.V0);
        assertThatThrownBy(() -> writer.writeNegotiationRound(List.of(NAK), SideBand.NONE))
                .isSameAs(failure);
        assertThatThrownBy(writer::flush).isSameAs(failure);
    }
    private static GitProtocolContext.Writer writer(OutputStream output, GitProtocolVersion version) {
        return new GitProtocolContext(new BufferedByteInputV2(InputStream.nullInputStream()),
                new OutputStreamBufferedByteOutput(output), version, GitTransport.SSH).writer();
    }

}
