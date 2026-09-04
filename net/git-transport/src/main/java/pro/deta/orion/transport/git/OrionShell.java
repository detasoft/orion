package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.common.io.IoInputStream;
import org.apache.sshd.common.io.IoOutputStream;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.Signal;
import org.apache.sshd.server.SignalListener;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.AsyncCommandStreamsAware;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.shell.ShellFactory;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.command.CommandDispatcher;
import pro.deta.orion.command.CommandNavigator;
import pro.deta.orion.command.terminal.InteractiveTerminal;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.transport.git.ssh.CloseOnDestroyCommand;
import pro.deta.orion.transport.git.auth.RootSshKeyEnrollmentSession;
import pro.deta.orion.transport.git.auth.OrionSshAuthenticator;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static pro.deta.orion.transport.git.GitSshTransportService.SSH_AUTHENTICATED_USER;

/**
 * @AiRule The session reader must be one dedicated virtual thread over Mina's complete async stream contract.
 * Adapters wait only through future listeners and AQS latches, never Mina future await/verify or synchronous
 * channel streams, because those paths can pin a Java 21 carrier. Command handlers belong on
 * {@link OrionExecutor}; result delivery belongs on a bounded-per-session completion virtual thread.
 * Lifecycle and output coordination must not hold intrinsic locks.
 */
@Slf4j
@Singleton
public final class OrionShell implements ShellFactory {
    private static final int DEFAULT_COLUMNS = 80;

    private final CommandDispatcher dispatcher;
    private final CommandNavigator navigator;
    private final OrionExecutor executor;

    @Inject
    public OrionShell(
            CommandDispatcher dispatcher,
            CommandNavigator navigator,
            OrionExecutor executor) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public Command createShell(ChannelSession channel) {
        if (RootSshKeyEnrollmentSession.isRestricted(channel.getSession())) {
            return new RecoveryRejectedShell();
        }
        return new TerminalCommand(channel);
    }

    private static final class RecoveryRejectedShell extends CloseOnDestroyCommand {
        @Override
        public void start(ChannelSession channel, Environment environment) {
            exitCallback.onExit(1);
        }
    }

    private final class TerminalCommand extends CloseOnDestroyCommand implements AsyncCommandStreamsAware {
        private final ChannelSession channel;
        private final AtomicReference<InteractiveTerminal> terminal = new AtomicReference<>();
        private final AtomicReference<Thread> reader = new AtomicReference<>();
        private final AtomicReference<Environment> environment = new AtomicReference<>();
        private final AtomicReference<SignalListener> resizeListener = new AtomicReference<>();
        private final AtomicBoolean listenerRemoved = new AtomicBoolean();
        private final AtomicBoolean destroyed = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final ReentrantLock startDestroyLock = new ReentrantLock();
        private final AtomicReference<IoInputStream> asyncInput = new AtomicReference<>();
        private final AtomicReference<IoOutputStream> asyncOutput = new AtomicReference<>();
        private final AtomicReference<IoOutputStream> asyncError = new AtomicReference<>();

        private TerminalCommand(ChannelSession channel) {
            this.channel = Objects.requireNonNull(channel, "channel");
        }

        @Override
        public void start(ChannelSession ignored, Environment currentEnvironment) throws IOException {
            startDestroyLock.lock();
            try {
                if (terminal.get() != null || destroyed.get()) {
                    throw new IOException("interactive terminal has already been started or destroyed");
                }
                MinaAsyncInputStream terminalInput = new MinaAsyncInputStream(required(asyncInput, "input"));
                MinaAsyncOutputStream terminalOutput =
                        new MinaAsyncOutputStream(required(asyncOutput, "output"));
                InteractiveTerminal created = new InteractiveTerminal(
                        dispatcher,
                        navigator,
                        executor,
                        connection(),
                        terminalOutput,
                        supportsAnsi(currentEnvironment),
                        terminalColumns(currentEnvironment));
                terminal.set(created);
                installResizeListener(currentEnvironment, created);
                Thread createdReader = Thread.ofVirtual()
                        .name("orion-ssh-terminal-", 0)
                        .unstarted(() -> runTerminal(created, terminalInput));
                reader.set(createdReader);
                createdReader.start();
            } finally {
                startDestroyLock.unlock();
            }
        }

        @Override
        public void setIoInputStream(IoInputStream input) {
            asyncInput.set(Objects.requireNonNull(input, "input"));
        }

        @Override
        public void setIoOutputStream(IoOutputStream output) {
            asyncOutput.set(Objects.requireNonNull(output, "output"));
        }

        @Override
        public void setIoErrorStream(IoOutputStream error) {
            asyncError.set(Objects.requireNonNull(error, "error"));
        }

        @Override
        public void destroy(ChannelSession ignored) {
            startDestroyLock.lock();
            try {
                if (!destroyed.compareAndSet(false, true)) {
                    return;
                }
            } finally {
                startDestroyLock.unlock();
            }
            InteractiveTerminal current = terminal.get();
            if (current != null) {
                current.close();
            } else {
                Thread currentReader = reader.get();
                if (currentReader != null) {
                    currentReader.interrupt();
                }
            }
            closeImmediately(asyncInput.get());
            closeImmediately(asyncOutput.get());
            removeResizeListener();
            closeImmediately(asyncError.get());
            super.destroy(channel);
            complete(0);
        }

        private void runTerminal(InteractiveTerminal created, InputStream terminalInput) {
            int code = 1;
            try {
                code = created.run(terminalInput);
            } catch (RuntimeException exception) {
                log.warn("Interactive SSH terminal failed", exception);
            } finally {
                created.close();
                complete(code);
            }
        }

        private static <T> T required(AtomicReference<T> reference, String name) throws IOException {
            T value = reference.get();
            if (value == null) {
                throw new IOException("missing asynchronous terminal " + name);
            }
            return value;
        }

        private static void closeImmediately(org.apache.sshd.common.Closeable stream) {
            if (stream != null) {
                stream.close(true);
            }
        }

        private InteractiveTerminal.Connection connection() {
            String requestId = UUID.randomUUID().toString();
            UserIdentity identity = channel.getSession().getAttribute(SSH_AUTHENTICATED_USER);
            SecurityContext securityContext = SecurityContext.createContext()
                    .withUserIdentity(identity)
                    .withSshConnectionCredentials(
                            OrionSshAuthenticator.connectionCredentials(channel.getSession()))
                    .withRequestId(requestId);
            return new InteractiveTerminal.Connection(
                    securityContext,
                    channel.getSession().getUsername(),
                    requestId,
                    channel.getSession().toString(),
                    String.valueOf(channel.getSession().getRemoteAddress()),
                    Map.of("transport", "ssh", "requestType", "shell"));
        }

        private void installResizeListener(
                Environment currentEnvironment,
                InteractiveTerminal currentTerminal) {
            if (currentEnvironment == null) {
                return;
            }
            SignalListener listener = (ignored, signal) -> {
                if (signal == Signal.WINCH) {
                    currentTerminal.resize(terminalColumns(currentEnvironment));
                }
            };
            environment.set(currentEnvironment);
            resizeListener.set(listener);
            currentEnvironment.addSignalListener(listener, Signal.WINCH);
        }

        private void removeResizeListener() {
            Environment installedEnvironment = environment.get();
            SignalListener listener = resizeListener.get();
            if (installedEnvironment != null
                    && listener != null
                    && listenerRemoved.compareAndSet(false, true)) {
                installedEnvironment.removeSignalListener(listener);
            }
        }

        private void complete(int code) {
            removeResizeListener();
            if (completed.compareAndSet(false, true)) {
                exitCallback.onExit(code);
            }
        }
    }

    private static boolean supportsAnsi(Environment environment) {
        if (environment == null) {
            return false;
        }
        String term = environment.getEnv().get(Environment.ENV_TERM);
        return term != null && !term.isBlank() && !term.equalsIgnoreCase("dumb");
    }

    private static int terminalColumns(Environment environment) {
        if (environment == null) {
            return DEFAULT_COLUMNS;
        }
        String value = environment.getEnv().get(Environment.ENV_COLUMNS);
        if (value == null) {
            return DEFAULT_COLUMNS;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : DEFAULT_COLUMNS;
        } catch (NumberFormatException ignored) {
            return DEFAULT_COLUMNS;
        }
    }
}
