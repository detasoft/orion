package pro.deta.orion.command.terminal;

import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.command.CommandCancellation;
import pro.deta.orion.command.CommandCompletion;
import pro.deta.orion.command.CommandContext;
import pro.deta.orion.command.CommandDispatcher;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandLocation;
import pro.deta.orion.command.CommandNavigation;
import pro.deta.orion.command.CommandNavigator;
import pro.deta.orion.command.CommandPath;
import pro.deta.orion.command.CommandPresentation;
import pro.deta.orion.command.CommandRequest;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.render.RenderedCommand;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class InteractiveTerminal implements AutoCloseable {
    private static final int INPUT_BUFFER_SIZE = 256;
    private static final int HISTORY_LIMIT = 100;
    private static final int LINE_LIMIT = 4096;

    private final CommandDispatcher dispatcher;
    private final CommandNavigator navigator;
    private final ExecutorService executor;
    private final Connection connection;
    private final TerminalDisplay display;
    private final TerminalCommandRenderer renderer = new TerminalCommandRenderer();
    private final TerminalLineEditor editor = new TerminalLineEditor(HISTORY_LIMIT, LINE_LIMIT);
    private final AtomicInteger columns;
    private final AtomicReference<CommandPath> currentPath = new AtomicReference<>(CommandPath.root());
    private final AtomicReference<ActiveCommand> active = new AtomicReference<>();
    private final AtomicReference<InputStream> input = new AtomicReference<>();
    private final AtomicReference<Thread> reader = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger exitCode = new AtomicInteger();

    public InteractiveTerminal(
            CommandDispatcher dispatcher,
            CommandNavigator navigator,
            ExecutorService executor,
            Connection connection,
            OutputStream output,
            boolean ansi,
            int columns) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.connection = Objects.requireNonNull(connection, "connection");
        display = new TerminalDisplay(output, ansi);
        if (columns < 0) {
            throw new IllegalArgumentException("columns must not be negative");
        }
        this.columns = new AtomicInteger(columns);
    }

    public int run(InputStream source) {
        Objects.requireNonNull(source, "source");
        if (!input.compareAndSet(null, source)) {
            throw new IllegalStateException("terminal has already been run");
        }
        reader.set(Thread.currentThread());
        try {
            prompt();
            byte[] buffer = new byte[INPUT_BUFFER_SIZE];
            while (!closed.get()) {
                int count = source.read(buffer, 0, buffer.length);
                if (count < 0) {
                    shutdown(0);
                    break;
                }
                consume(buffer, count);
            }
        } catch (IOException exception) {
            if (!closed.get()) {
                shutdown(0);
            }
        } finally {
            reader.compareAndSet(Thread.currentThread(), null);
        }
        return exitCode.get();
    }

    public void resize(int terminalColumns) {
        if (terminalColumns >= 0) {
            columns.set(terminalColumns);
        }
    }

    @Override
    public void close() {
        shutdown(exitCode.get());
    }

    private void consume(byte[] buffer, int count) {
        boolean bellWritten = false;
        ActiveCommand discardedFor = null;
        for (int index = 0; index < count && !closed.get(); index++) {
            ActiveCommand current = active.get();
            if (discardedFor != null || current != null) {
                if (discardedFor == null) {
                    discardedFor = current;
                }
                if (buffer[index] == 3 && active.get() == discardedFor) {
                    if (cancelActive(discardedFor)) {
                        discardedFor = null;
                    } else {
                        bellWritten = true;
                    }
                } else {
                    bellWritten = true;
                }
                continue;
            }
            List<TerminalInputEvent> events = editor.accept(buffer, index, 1);
            for (TerminalInputEvent event : events) {
                handle(event);
            }
        }
        if (bellWritten) {
            write("\u0007");
        }
    }

    private void handle(TerminalInputEvent event) {
        if (event instanceof TerminalInputEvent.Redraw) {
            redraw();
        } else if (event instanceof TerminalInputEvent.Submit submit) {
            write("\r\n");
            submit(submit.line());
        } else if (event instanceof TerminalInputEvent.Complete) {
            complete();
        } else if (event instanceof TerminalInputEvent.Cancel) {
            idleCancel();
        } else if (event instanceof TerminalInputEvent.EndOfInput && editor.line().isEmpty()) {
            shutdown(0);
        }
    }

    private void submit(String enteredLine) {
        String control = enteredLine.trim();
        if (control.isEmpty()) {
            prompt();
            return;
        }
        if (control.equals("quit")) {
            shutdown(0);
            return;
        }
        if (control.equals("?") || control.equalsIgnoreCase("help")) {
            help();
            prompt();
            return;
        }
        if (isSingleToken(control)) {
            CommandContext navigationContext = context(CommandCancellation.never());
            CommandNavigation navigation = navigator.navigate(navigationContext, currentPath.get(), control);
            if (navigation instanceof CommandNavigation.Located located) {
                currentPath.set(located.location().path());
                prompt();
                return;
            }
            if (isExplicitPath(control)) {
                renderNavigationFailure(navigation);
                prompt();
                return;
            }
        }
        dispatch(enteredLine);
    }

    private void dispatch(String line) {
        ActiveCommand command = new ActiveCommand();
        if (!active.compareAndSet(null, command)) {
            write("\u0007");
            return;
        }
        try {
            Future<?> future = executor.submit(() -> dispatch(command, line));
            command.attach(future);
        } catch (RuntimeException exception) {
            if (active.compareAndSet(command, null)) {
                render(new CommandResult.Failure(
                        CommandFailureCode.HANDLER_FAILED,
                        "Command handler failed",
                        List.of()));
                prompt();
            }
        }
    }

    private void dispatch(ActiveCommand command, String line) {
        CommandResult result;
        try {
            result = Objects.requireNonNull(
                    dispatcher.dispatch(new CommandRequest(line, context(command.cancellation))),
                    "dispatcher result");
        } catch (RuntimeException exception) {
            result = new CommandResult.Failure(
                    CommandFailureCode.HANDLER_FAILED,
                    "Command handler failed",
                    List.of());
        }
        CommandResult completedResult = result;
        Thread completion = Thread.ofVirtual()
                .name("orion-terminal-completion-", 0)
                .unstarted(() -> complete(command, completedResult));
        command.attachCompletion(completion);
        completion.start();
    }

    private void complete(ActiveCommand command, CommandResult result) {
        if (!command.beginCompletion() || active.get() != command || closed.get()) {
            return;
        }
        try {
            render(result);
            if (result instanceof CommandResult.Exit exit) {
                command.finishCompletion();
                shutdown(exit.exitCode());
            } else if (!closed.get()) {
                prompt();
            }
        } finally {
            command.finishCompletion();
            active.compareAndSet(command, null);
        }
    }

    private boolean cancelActive(ActiveCommand command) {
        if (!command.cancelRunning()) {
            return false;
        }
        active.compareAndSet(command, null);
        write("^C\r\nCANCELLED: Command was cancelled\r\n");
        editor.clear();
        prompt();
        return true;
    }

    private void idleCancel() {
        editor.clear();
        write("^C\r\n");
        prompt();
    }

    private void complete() {
        String line = editor.line();
        int characterCursor = line.offsetByCodePoints(0, editor.cursor());
        CommandCompletion.Result result = navigator.complete(
                context(CommandCancellation.never()),
                currentPath.get(),
                line,
                characterCursor);
        int codePointCursor = result.line().codePointCount(0, result.cursor());
        boolean changed = !result.line().equals(line) || codePointCursor != editor.cursor();
        editor.replace(result.line(), codePointCursor);
        if (!changed && result.candidates().size() > 1) {
            write("\r\n" + TerminalDisplay.columns(result.candidates(), columns.get()));
        }
        redraw();
    }

    private void help() {
        CommandNavigation navigation = navigator.locate(
                context(CommandCancellation.never()),
                currentPath.get());
        if (navigation instanceof CommandNavigation.Located located) {
            List<String> entries = navigator.visibleEntries(
                    context(CommandCancellation.never()),
                    located.location());
            write(TerminalDisplay.columns(entries, columns.get()));
        }
    }

    private void renderNavigationFailure(CommandNavigation navigation) {
        if (navigation instanceof CommandNavigation.Ambiguous ambiguous) {
            render(new CommandResult.Failure(
                    CommandFailureCode.AMBIGUOUS_RESOURCE,
                    "Resource selector is ambiguous",
                    ambiguous.candidates()));
        } else if (navigation instanceof CommandNavigation.Missing) {
            render(new CommandResult.Failure(
                    CommandFailureCode.MISSING_RESOURCE,
                    "Resource was not found",
                    List.of()));
        } else if (navigation instanceof CommandNavigation.Unavailable) {
            render(new CommandResult.Failure(
                    CommandFailureCode.SERVICE_UNAVAILABLE,
                    "Resource service is unavailable",
                    List.of()));
        } else if (navigation instanceof CommandNavigation.AccessDenied) {
            render(new CommandResult.Failure(
                    CommandFailureCode.ACCESS_DENIED,
                    "Access denied",
                    List.of()));
        } else if (navigation instanceof CommandNavigation.Failed) {
            render(new CommandResult.Failure(
                    CommandFailureCode.HANDLER_FAILED,
                    "Resource lookup failed",
                    List.of()));
        } else {
            render(new CommandResult.Failure(
                    CommandFailureCode.UNKNOWN_PATH,
                    "Unknown command path",
                    List.of()));
        }
    }

    private void render(CommandResult result) {
        RenderedCommand rendered = renderer.render(result, columns.get());
        write(rendered.stdout());
        write(rendered.stderr());
    }

    private CommandContext context(CommandCancellation cancellation) {
        return new CommandContext(
                connection.securityContext(),
                connection.requestId(),
                connection.sessionId(),
                connection.sourceAddress(),
                currentPath.get(),
                new CommandPresentation(true, display.ansi(), columns.get()),
                cancellation,
                connection.auditMetadata());
    }

    private void prompt() {
        String value = TerminalDisplay.prompt(connection.username(), currentPath.get());
        try {
            display.redraw(value, editor.line(), editor.cursor());
        } catch (IOException exception) {
            shutdown(1);
        }
    }

    private void redraw() {
        prompt();
    }

    private void write(String value) {
        if (value.isEmpty() || closed.get()) {
            return;
        }
        try {
            display.write(value);
        } catch (IOException exception) {
            shutdown(1);
        }
    }

    private void shutdown(int requestedExitCode) {
        if (requestedExitCode != 0) {
            exitCode.compareAndSet(0, requestedExitCode);
        }
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ActiveCommand command = active.getAndSet(null);
        if (command != null) {
            command.cancel();
        }
        Thread activeReader = reader.get();
        if (activeReader != null && activeReader != Thread.currentThread()) {
            activeReader.interrupt();
        }
        closeInput();
        closeDisplay();
    }

    private void closeInput() {
        InputStream source = input.get();
        if (source != null) {
            try {
                source.close();
            } catch (IOException ignored) {
                // Disconnect cleanup is best effort after the lifecycle has atomically closed.
            }
        }
    }

    private void closeDisplay() {
        try {
            display.close();
        } catch (IOException ignored) {
            exitCode.compareAndSet(0, 1);
        }
    }

    private static boolean isExplicitPath(String line) {
        return line.startsWith("/") || line.equals("..") || line.contains("/");
    }

    private static boolean isSingleToken(String line) {
        return line.codePoints().noneMatch(Character::isWhitespace);
    }

    public record Connection(
            SecurityContext securityContext,
            String username,
            String requestId,
            String sessionId,
            String sourceAddress,
            Map<String, String> auditMetadata) {
        public Connection {
            Objects.requireNonNull(securityContext, "securityContext");
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(sourceAddress, "sourceAddress");
            Objects.requireNonNull(auditMetadata, "auditMetadata");
            auditMetadata = Map.copyOf(auditMetadata);
        }
    }

    private static final class ActiveCommand {
        private enum State {
            RUNNING,
            COMPLETING,
            COMPLETED,
            CANCELLED
        }

        private final TerminalCancellation cancellation = new TerminalCancellation();
        private final AtomicReference<Future<?>> future = new AtomicReference<>();
        private final AtomicReference<Thread> completion = new AtomicReference<>();
        private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);

        private void attach(Future<?> submitted) {
            future.set(submitted);
            if (cancellation.isCancelled()) {
                submitted.cancel(true);
            }
        }

        private void cancel() {
            State previous = state.getAndSet(State.CANCELLED);
            if (previous == State.CANCELLED || previous == State.COMPLETED) {
                return;
            }
            cancellation.cancel();
            Future<?> submitted = future.get();
            if (submitted != null) {
                submitted.cancel(true);
            }
            interruptCompletion();
        }

        private boolean cancelRunning() {
            if (!state.compareAndSet(State.RUNNING, State.CANCELLED)) {
                return false;
            }
            cancellation.cancel();
            Future<?> submitted = future.get();
            if (submitted != null) {
                submitted.cancel(true);
            }
            interruptCompletion();
            return true;
        }

        private void attachCompletion(Thread submitted) {
            completion.set(submitted);
            if (state.get() == State.CANCELLED) {
                submitted.interrupt();
            }
        }

        private void interruptCompletion() {
            Thread submitted = completion.get();
            if (submitted != null) {
                submitted.interrupt();
            }
        }

        private boolean beginCompletion() {
            return state.compareAndSet(State.RUNNING, State.COMPLETING);
        }

        private void finishCompletion() {
            state.compareAndSet(State.COMPLETING, State.COMPLETED);
        }
    }
}
