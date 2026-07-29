package pro.deta.orion.transport.git.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.continuation.RuntimeFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;

import java.util.Objects;

/**
 * Minimal Netty ownership and scheduling bridge for
 * {@link GitMinimalWireMachine}. Git protocol state remains in the machine.
 */
public final class GitMinimalWireHandler extends ChannelInboundHandlerAdapter {
    private static final System.Logger LOG =
            System.getLogger(GitMinimalWireHandler.class.getName());

    private final GitMinimalWireMachine machine;
    private boolean yieldScheduled;
    private boolean closed;

    public GitMinimalWireHandler(GitMinimalWireMachine machine) {
        this.machine = Objects.requireNonNull(machine, "machine");
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!(message instanceof ByteBuf input)) {
            context.fireChannelRead(message);
            return;
        }
        try {
            RuntimeFlow flow = machine.accept(input);
            handleInitialFlow(context, flow, input);
        } catch (Throwable error) {
            fail(context, error);
        } finally {
            ReferenceCountUtil.release(input);
        }
    }

    private void handleInitialFlow(
            ChannelHandlerContext context,
            RuntimeFlow flow,
            ByteBuf input) {
        if (flow instanceof ContinuationFlow.Yield<?> yield) {
            scheduleYield(context, yield, input.retain());
        } else {
            handleCompletedFlow(context, flow);
        }
    }

    private void handleCompletedFlow(ChannelHandlerContext context, RuntimeFlow flow) {
        if (flow instanceof RuntimeFlow.Error error) {
            LOG.log(System.Logger.Level.WARNING, error.message(), error.throwable());
        } else if (flow instanceof RuntimeFlow.Terminal) {
            // TODO
//            machine.terminalError().ifPresent(context::fireExceptionCaught);
            closeMachine(context);
            context.close();
        }
    }

    private void scheduleYield(
            ChannelHandlerContext context,
            ContinuationFlow.Yield<?> yield,
            ByteBuf retainedInput) {
        if (closed || yieldScheduled) {
            retainedInput.release();
            return;
        }
        yieldScheduled = true;
        try {
            context.executor().execute(() -> runYield(context, yield.task(), retainedInput));
        } catch (Throwable error) {
            yieldScheduled = false;
            retainedInput.release();
            throw error;
        }
    }

    private void runYield(
            ChannelHandlerContext context,
            Runnable task,
            ByteBuf retainedInput) {
        yieldScheduled = false;
        if (closed) {
            retainedInput.release();
            return;
        }
        try {
            task.run();
            RuntimeFlow flow = machine.resumeTask();
            if (flow instanceof ContinuationFlow.Yield<?> yield) {
                scheduleYield(context, yield, retainedInput);
            } else {
                retainedInput.release();
                handleCompletedFlow(context, flow);
            }
        } catch (Throwable error) {
            retainedInput.release();
            fail(context, error);
        }
    }

    private void fail(ChannelHandlerContext context, Throwable error) {
        closeMachine(context);
        context.fireExceptionCaught(error);
        context.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        closeMachine(context);
        super.channelInactive(context);
    }

    private void closeMachine(ChannelHandlerContext context) {
        if (closed) {
            return;
        }
        closed = true;
        try {
            machine.close();
        } catch (Throwable closeError) {
            context.fireExceptionCaught(closeError);
        }
    }
}
