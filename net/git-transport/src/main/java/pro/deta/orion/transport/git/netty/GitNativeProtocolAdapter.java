package pro.deta.orion.transport.git.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.git.parser.wire.GitInitialServiceRequest;
import pro.deta.orion.git.parser.wire.GitInitialServiceRequestParser;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitWireError;
import pro.deta.orion.git.parser.wire.GitWireException;

import java.util.function.Function;

/**
 * Per-connection Netty handler that accumulates data until the initial Git service
 * request pkt-line is complete, then hands all subsequent bytes directly to
 * {@link GitMinimalWireMachine}.
 *
 * <p>NOT {@code @Sharable} — a new instance is created per connection.
 */
@Slf4j
public final class GitNativeProtocolAdapter extends ChannelInboundHandlerAdapter {

    enum Phase { INITIAL, SERVING }

    private final Function<String, GitRepository> repoLookup; // Phase 2: used to resolve repo
    private final ByteBufAllocator alloc;
    Phase phase = Phase.INITIAL;
    private CompositeByteBuf accumulator;
    private GitMinimalWireMachine machine;

    // FrameConsumer — fires on flush/delimiter/response-end
    private final GitMinimalWireMachine.FrameConsumer frameConsumer = (control, flow) -> {
        // Phase 2: dispatch to service when section complete
    };

    // StructuredPayloadConsumer — fires on each data pkt-line payload
    private final GitMinimalWireMachine.StructuredPayloadConsumer payloadConsumer =
            (control, payload) -> {
                // Phase 2: accumulate into section buffer
                payload.release();
            };

    // RawTargetFactory — Phase 2: PackIngestor
    private final GitMinimalWireMachine.RawTargetFactory rawTargetFactory =
            control -> buf -> buf.release();

    public GitNativeProtocolAdapter(
            Function<String, GitRepository> repoLookup,
            ByteBufAllocator alloc) {
        this.repoLookup = repoLookup;
        this.alloc = alloc;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        accumulator = alloc.compositeBuffer();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;
        if (phase == Phase.INITIAL) {
            handleInitial(ctx, buf);
        } else {
            machine.accept(buf);
            buf.release();
        }
    }

    private void handleInitial(ChannelHandlerContext ctx, ByteBuf chunk) {
        accumulator.addComponent(true, chunk);
        ByteBuf view = accumulator.duplicate();
        try {
            GitInitialServiceRequest request = GitInitialServiceRequestParser.read(view);
            // advance accumulator past consumed bytes
            accumulator.readerIndex(view.readerIndex());
            // Phase 2: resolve repo via repoLookup, write real advertisement to ctx
            machine = new GitMinimalWireMachine(alloc, frameConsumer, payloadConsumer, rawTargetFactory);
            if (accumulator.isReadable()) {
                machine.accept(accumulator.slice());
            }
            accumulator.release();
            accumulator = null;
            phase = Phase.SERVING;
        } catch (GitWireException e) {
            if (e.error().kind() == GitWireError.Kind.INCOMPLETE_PAYLOAD
                    || e.error().kind() == GitWireError.Kind.INCOMPLETE_HEADER) {
                return; // need more data — keep accumulating
            }
            log.warn("Invalid initial Git service request: {}", e.getMessage());
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (machine != null) {
            machine.close();
            machine = null;
        }
        if (accumulator != null) {
            accumulator.release();
            accumulator = null;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Git native protocol error", cause);
        ctx.close();
    }
}
