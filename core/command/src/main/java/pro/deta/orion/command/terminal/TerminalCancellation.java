package pro.deta.orion.command.terminal;

import pro.deta.orion.command.CommandCancellation;

import java.util.concurrent.atomic.AtomicBoolean;

public final class TerminalCancellation implements CommandCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }
}
