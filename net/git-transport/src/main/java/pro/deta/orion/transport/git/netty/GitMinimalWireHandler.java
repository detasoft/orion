package pro.deta.orion.transport.git.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.continuation.ContinuationTask;
import pro.deta.orion.continuation.RuntimeFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

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
        if (closed) {
            yieldScheduled = false;
            retainedInput.release();
            return;
        }
        try {
            task.run();
            CompletionStage<Void> completion =
                    ContinuationTask.completionOf(task);
            if (completion.toCompletableFuture().isDone()) {
                completeYield(context, retainedInput, completedFailure(completion));
            } else {
                completion.whenComplete((ignored, failure) ->
                        context.executor().execute(() ->
                                completeYield(
                                        context,
                                        retainedInput,
                                        failure)));
            }
        } catch (Throwable error) {
            yieldScheduled = false;
            retainedInput.release();
            fail(context, error);
        }
    }

    private void completeYield(
            ChannelHandlerContext context,
            ByteBuf retainedInput,
            Throwable failure) {
        if (closed) {
            yieldScheduled = false;
            retainedInput.release();
            return;
        }
        if (failure != null) {
            yieldScheduled = false;
            retainedInput.release();
            fail(context, unwrap(failure));
            return;
        }
        try {
            RuntimeFlow flow = machine.resumeTask();
            if (flow instanceof ContinuationFlow.Yield<?> yield) {
                yieldScheduled = false;
                scheduleYield(context, yield, retainedInput);
            } else {
                yieldScheduled = false;
                retainedInput.release();
                handleCompletedFlow(context, flow);
            }
        } catch (Throwable error) {
            yieldScheduled = false;
            retainedInput.release();
            fail(context, error);
        }
    }

    private static Throwable completedFailure(
            CompletionStage<Void> completion) {
        try {
            completion.toCompletableFuture().getNow(null);
            return null;
        } catch (CompletionException error) {
            return unwrap(error);
        } catch (CancellationException error) {
            return error;
        }
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException completionException
                && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return error;
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
