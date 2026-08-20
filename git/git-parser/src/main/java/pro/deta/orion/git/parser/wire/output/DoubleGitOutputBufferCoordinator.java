package pro.deta.orion.git.parser.wire.output;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.parser.wire.GitNativeClientWrite;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public final class DoubleGitOutputBufferCoordinator
        implements GitOutputBufferCoordinator {
    private final GitNativeClientWrite write;
    private final Slot[] slots;
    private final ArrayDeque<CompletionEvent> completions =
            new ArrayDeque<>();
    private int currentSlot;
    private CompletableFuture<Void> writableWaiter;
    private CompletableFuture<Void> drainWaiter;
    private Throwable failure;
    private boolean closed;

    public DoubleGitOutputBufferCoordinator(
            ByteBuf first,
            ByteBuf second,
            GitNativeClientWrite write) {
        this.write = Objects.requireNonNull(write, "write");
        this.slots = new Slot[] {
                new Slot(0, Objects.requireNonNull(first, "first")),
                new Slot(1, Objects.requireNonNull(second, "second"))
        };
        requireFixedCapacity(first, "first");
        requireFixedCapacity(second, "second");
        if (first.capacity() != second.capacity()) {
            throw new IllegalArgumentException(
                    "Output buffers must have the same capacity");
        }
    }

    private static void requireFixedCapacity(
            ByteBuf buffer,
            String name) {
        if (buffer.capacity() <= 0
                || buffer.capacity() != buffer.maxCapacity()) {
            throw new IllegalArgumentException(
                    name + " output buffer must have fixed positive capacity");
        }
    }

    @Override
    public synchronized ByteBuf writableBuffer() {
        observeCompletions();
        ensureUsable();
        Slot slot = writableSlot();
        if (slot == null) {
            throw new IllegalStateException(
                    "No writable Git output buffer is available");
        }
        currentSlot = slot.index;
        return slot.buffer;
    }

    @Override
    public synchronized CompletionStage<Void> submitReady() {
        observeCompletions();
        ensureUsable();
        Slot slot = slots[currentSlot];
        if (slot.state != SlotState.WRITABLE) {
            throw new IllegalStateException(
                    "Current Git output buffer is not writable");
        }
        if (!slot.buffer.isReadable()) {
            return CompletableFuture.completedFuture(null);
        }
        slot.state = SlotState.READY;
        CompletionStage<Void> completion;
        try {
            completion = Objects.requireNonNull(
                    write.write(slot.buffer),
                    "write completion");
        } catch (Throwable writeFailure) {
            markFailure(writeFailure);
            return failedStage(writeFailure);
        }
        slot.state = SlotState.IN_FLIGHT;
        completion.whenComplete((ignored, writeFailure) ->
                enqueueCompletion(slot, writeFailure));
        Slot next = writableSlot();
        if (next != null) {
            currentSlot = next.index;
        }
        return completion;
    }

    @Override
    public synchronized CompletionStage<Void> awaitWritable() {
        observeCompletions();
        if (failure != null) {
            return failedStage(failure);
        }
        if (closed) {
            return failedStage(new IllegalStateException(
                    "Git output buffer coordinator is closed"));
        }
        if (writableSlot() != null) {
            return CompletableFuture.completedFuture(null);
        }
        if (writableWaiter == null || writableWaiter.isDone()) {
            writableWaiter = new CompletableFuture<>();
        }
        return writableWaiter;
    }

    @Override
    public synchronized CompletionStage<Void> finish() {
        observeCompletions();
        if (failure != null) {
            return failedStage(failure);
        }
        if (closed) {
            return failedStage(new IllegalStateException(
                    "Git output buffer coordinator is closed"));
        }
        Slot slot = slots[currentSlot];
        if (slot.state == SlotState.WRITABLE && slot.buffer.isReadable()) {
            CompletionStage<Void> submitted = submitReady();
            observeCompletions();
            if (failure != null) {
                return failedStage(failure);
            }
            if (!hasInFlight()) {
                return submitted;
            }
        }
        if (!hasInFlight()) {
            return CompletableFuture.completedFuture(null);
        }
        if (drainWaiter == null || drainWaiter.isDone()) {
            drainWaiter = new CompletableFuture<>();
        }
        return drainWaiter;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        IllegalStateException closeFailure = new IllegalStateException(
                "Git output buffer coordinator is closed");
        if (writableWaiter != null) {
            writableWaiter.completeExceptionally(closeFailure);
        }
        if (drainWaiter != null) {
            drainWaiter.completeExceptionally(closeFailure);
        }
        for (Slot slot : slots) {
            slot.state = SlotState.CLOSED;
            if (slot.buffer.refCnt() > 0) {
                slot.buffer.release();
            }
        }
    }

    private void observeCompletions() {
        CompletionEvent event;
        while ((event = completions.pollFirst()) != null) {
            if (event.failure != null) {
                markFailure(unwrap(event.failure));
                continue;
            }
            if (!closed && event.slot.state != SlotState.CLOSED) {
                event.slot.buffer.clear();
                event.slot.state = SlotState.WRITABLE;
            }
        }
        if (failure == null && !closed && writableSlot() != null
                && writableWaiter != null) {
            writableWaiter.complete(null);
        }
        if (failure == null && !closed && !hasInFlight()
                && drainWaiter != null) {
            drainWaiter.complete(null);
        }
    }

    private void enqueueCompletion(
            Slot slot,
            Throwable writeFailure) {
        synchronized (this) {
            completions.addLast(new CompletionEvent(slot, writeFailure));
            observeCompletions();
        }
    }

    private void ensureUsable() {
        if (failure != null) {
            throw new CompletionException(failure);
        }
        if (closed) {
            throw new IllegalStateException(
                    "Git output buffer coordinator is closed");
        }
    }

    private Slot writableSlot() {
        for (Slot slot : slots) {
            if (slot.state == SlotState.WRITABLE) {
                return slot;
            }
        }
        return null;
    }

    private boolean hasInFlight() {
        for (Slot slot : slots) {
            if (slot.state == SlotState.IN_FLIGHT) {
                return true;
            }
        }
        return false;
    }

    private void markFailure(Throwable writeFailure) {
        if (failure == null) {
            failure = writeFailure;
        }
        if (writableWaiter != null) {
            writableWaiter.completeExceptionally(failure);
        }
        if (drainWaiter != null) {
            drainWaiter.completeExceptionally(failure);
        }
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException completionException
                && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return error;
    }

    private static CompletionStage<Void> failedStage(Throwable error) {
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(error);
        return failed;
    }

    private final class Slot {
        private final int index;
        private final ByteBuf buffer;
        private SlotState state = SlotState.WRITABLE;

        private Slot(
                int index,
                ByteBuf buffer) {
            this.index = index;
            this.buffer = buffer.clear();
        }
    }

    private record CompletionEvent(
            Slot slot,
            Throwable failure) {
    }

    private enum SlotState {
        WRITABLE,
        READY,
        IN_FLIGHT,
        CLOSED
    }
}
