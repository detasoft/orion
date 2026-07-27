package pro.deta.orion.git.parser.wire.receivepack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReceivePackAdvertisementWriterTest {
    private static final String SHA1_A = "a".repeat(40);
    private static final String SHA1_B = "b".repeat(40);

    private final GitPktLineWriter pktLineWriter = new GitPktLineWriter(UnpooledByteBufAllocator.DEFAULT);
    private final ReceivePackAdvertisementWriter writer = new ReceivePackAdvertisementWriter();

    private static final Set<ReceivePackCapability> STANDARD_CAPS = EnumSet.of(
            ReceivePackCapability.REPORT_STATUS,
            ReceivePackCapability.SIDE_BAND_64K,
            ReceivePackCapability.OBJECT_FORMAT,
            ReceivePackCapability.AGENT);

    @Test
    void writesEmptyRepoAdvertisement() {
        List<ByteBuf> packets = writer.write(pktLineWriter, Map.of(), STANDARD_CAPS);
        try {
            assertThat(ascii(packets)).hasSize(2);
            assertThat(packets.get(packets.size() - 1).readableBytes()).isEqualTo(4);

            String firstLine = payloadOf(packets.get(0));
            assertThat(firstLine).startsWith("0".repeat(40) + " capabilities^{}\0");
            assertThat(firstLine).contains("report-status");
            assertThat(firstLine).contains("side-band-64k");
            assertThat(firstLine).contains("object-format=sha1");
            assertThat(firstLine).contains("agent=orion-native/0.1");
        } finally {
            release(packets);
        }
    }

    @Test
    void writesFirstRefWithCapabilitiesAndFlush() {
        Map<String, String> refs = Map.of("refs/heads/main", SHA1_A);
        List<ByteBuf> packets = writer.write(pktLineWriter, refs, STANDARD_CAPS);
        try {
            assertThat(packets).hasSize(2);

            String firstLine = payloadOf(packets.get(0));
            assertThat(firstLine).startsWith(SHA1_A + " refs/heads/main\0");
            assertThat(firstLine).contains("report-status");
            assertThat(firstLine).contains("side-band-64k");

            assertThat(packets.get(1).readableBytes()).isEqualTo(4);
        } finally {
            release(packets);
        }
    }

    @Test
    void writesMultipleRefsFirstOneHasCapabilities() {
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("refs/heads/main", SHA1_A);
        refs.put("refs/heads/feature", SHA1_B);

        List<ByteBuf> packets = writer.write(pktLineWriter, refs, STANDARD_CAPS);
        try {
            assertThat(packets).hasSize(3);

            String firstLine = payloadOf(packets.get(0));
            assertThat(firstLine).contains("\0");

            String secondLine = payloadOf(packets.get(1));
            assertThat(secondLine).doesNotContain("\0");
            assertThat(secondLine).contains(SHA1_B + " refs/heads/feature");
        } finally {
            release(packets);
        }
    }

    @Test
    void writesNoCapabilitiesWhenSetIsEmpty() {
        Map<String, String> refs = Map.of("refs/heads/main", SHA1_A);
        List<ByteBuf> packets = writer.write(pktLineWriter, refs, EnumSet.noneOf(ReceivePackCapability.class));
        try {
            String firstLine = payloadOf(packets.get(0));
            assertThat(firstLine).isEqualTo(SHA1_A + " refs/heads/main");
        } finally {
            release(packets);
        }
    }

    private String payloadOf(ByteBuf packet) {
        byte[] bytes = new byte[packet.readableBytes()];
        packet.getBytes(packet.readerIndex(), bytes);
        String pktLine = new String(bytes, StandardCharsets.UTF_8);
        int payloadStart = 4;
        String payload = pktLine.substring(payloadStart);
        if (payload.endsWith("\n")) {
            payload = payload.substring(0, payload.length() - 1);
        }
        return payload;
    }

    private static List<String> ascii(List<ByteBuf> packets) {
        List<String> values = new ArrayList<>();
        for (ByteBuf packet : packets) {
            byte[] bytes = new byte[packet.readableBytes()];
            packet.getBytes(packet.readerIndex(), bytes);
            values.add(new String(bytes, StandardCharsets.US_ASCII));
        }
        return values;
    }

    private static void release(List<ByteBuf> packets) {
        for (ByteBuf packet : packets) {
            packet.release();
        }
    }
}
