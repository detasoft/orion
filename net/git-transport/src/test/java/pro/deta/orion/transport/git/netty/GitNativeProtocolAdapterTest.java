package pro.deta.orion.transport.git.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitNativeProtocolAdapterTest {

    @Test
    void acceptsInitialServiceRequest() {
        EmbeddedChannel ch = newChannel();

        ch.writeInbound(pktLineBuf("git-upload-pack /test.git\0host=localhost\0"));

        assertEquals(GitNativeProtocolAdapter.Phase.SERVING, adapter(ch).phase);
        assertFalse(ch.finish());
    }

    @Test
    void handlesFragmentedInitialRequest() {
        EmbeddedChannel ch = newChannel();
        byte[] full = pktLine("git-upload-pack /test.git\0host=localhost\0");
        int split = full.length / 2;

        ch.writeInbound(Unpooled.wrappedBuffer(full, 0, split));

        assertEquals(GitNativeProtocolAdapter.Phase.INITIAL, adapter(ch).phase);

        ch.writeInbound(Unpooled.wrappedBuffer(full, split, full.length - split));

        assertEquals(GitNativeProtocolAdapter.Phase.SERVING, adapter(ch).phase);
        assertFalse(ch.finish());
    }

    @Test
    void feedsSubsequentChunksToMachine() {
        EmbeddedChannel ch = newChannel();
        ch.writeInbound(pktLineBuf("git-upload-pack /test.git\0host=localhost\0"));
        assertEquals(GitNativeProtocolAdapter.Phase.SERVING, adapter(ch).phase);

        // flush pkt-line (0000) — machine accepts it without error; channel stays open
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[]{'0', '0', '0', '0'}));

        assertTrue(ch.isActive());
        assertFalse(ch.finish());
    }

    @Test
    void invalidInitialRequestClosesChannel() {
        EmbeddedChannel ch = newChannel();

        // "not-a-git-command /path" is rejected by the parser
        ch.writeInbound(pktLineBuf("not-a-git-command /path.git\0"));

        assertFalse(ch.isActive(), "channel should be closed after invalid request");
    }

    // -----------------------------------------------------------------------

    private static EmbeddedChannel newChannel() {
        return new EmbeddedChannel(
                new GitNativeProtocolAdapter(path -> null, io.netty.buffer.UnpooledByteBufAllocator.DEFAULT));
    }

    private static GitNativeProtocolAdapter adapter(EmbeddedChannel ch) {
        return (GitNativeProtocolAdapter) ch.pipeline().first();
    }

    private static ByteBuf pktLineBuf(String payload) {
        return Unpooled.wrappedBuffer(pktLine(payload));
    }

    private static byte[] pktLine(String payload) {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String length = "%04x".formatted(payloadBytes.length + 4);
        byte[] prefix = length.getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[prefix.length + payloadBytes.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(payloadBytes, 0, result, prefix.length, payloadBytes.length);
        return result;
    }
}
