package pro.deta.orion.git.client;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class GitBlockingClientExecutor {
    private GitBlockingClientExecutor() {
    }

    static <T> GitClientResult<T> execute(
            GitClientTransport transport,
            GitClientService service,
            URI remoteUri,
            GitClientOptions options,
            Operation<T> operation) {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(remoteUri, "remoteUri");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(operation, "operation");

        SessionState sessionState = new SessionState();
        FutureTask<GitClientResult<T>> task = new FutureTask<>(() ->
                run(transport, service, remoteUri, options, operation, sessionState));
        Thread worker = Thread.ofVirtual()
                .name("orion-git-client-" + service.name().toLowerCase())
                .start(task);
        try {
            return task.get(
                    options.operationTimeout().toNanos(),
                    TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            cancel(worker, task, sessionState);
            return failed(
                    GitClientFailure.Kind.TIMEOUT,
                    GitClientFailure.Phase.OPEN,
                    true,
                    "Git operation timed out",
                    error);
        } catch (InterruptedException error) {
            cancel(worker, task, sessionState);
            Thread.currentThread().interrupt();
            return failed(
                    GitClientFailure.Kind.CANCELLED,
                    GitClientFailure.Phase.OPEN,
                    true,
                    "Git operation was cancelled",
                    error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error fatalError) {
                throw fatalError;
            }
            return failed(
                    GitClientFailure.Kind.MALFORMED_RESPONSE,
                    GitClientFailure.Phase.OPEN,
                    false,
                    "Git client operation failed unexpectedly",
                    cause);
        }
    }

    private static <T> GitClientResult<T> run(
            GitClientTransport transport,
            GitClientService service,
            URI remoteUri,
            GitClientOptions options,
            Operation<T> operation,
            SessionState sessionState) {
        GitClientTransportSession session = null;
        GitClientResult<T> result = null;
        try {
            session = transport.open(service, remoteUri, options);
            if (!sessionState.opened(session)) {
                throw new GitClientTransportException(
                        GitClientFailure.Kind.CANCELLED,
                        true,
                        "Git operation was cancelled before the session opened");
            }
            result = new GitClientResult.Success<>(operation.run(session));
        } catch (GitClientProtocolException error) {
            result = new GitClientResult.Failed<>(error.failure());
        } catch (GitClientTransportException error) {
            result = failed(
                    error.kind(),
                    GitClientFailure.Phase.OPEN,
                    error.retryable(),
                    error.getMessage(),
                    error);
        } catch (EOFException error) {
            result = failed(
                    GitClientFailure.Kind.UNEXPECTED_END_OF_STREAM,
                    GitClientFailure.Phase.ADVERTISEMENT,
                    true,
                    "Remote Git session ended unexpectedly",
                    error);
        } catch (IOException error) {
            result = failed(
                    GitClientFailure.Kind.TRANSPORT_UNAVAILABLE,
                    GitClientFailure.Phase.OPEN,
                    true,
                    "Remote Git transport failed",
                    error);
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (IOException closeError) {
                    if (result instanceof GitClientResult.Failed<T> failed) {
                        if (failed.failure().cause() != null) {
                            failed.failure().cause().addSuppressed(closeError);
                        }
                    } else if (result != null) {
                        result = failed(
                                GitClientFailure.Kind.TRANSPORT_UNAVAILABLE,
                                GitClientFailure.Phase.CLOSE,
                                true,
                                "Remote Git session failed to close",
                                closeError);
                    }
                } finally {
                    sessionState.closed(session);
                }
            }
        }
        return result;
    }

    private static void cancel(
            Thread worker,
            FutureTask<?> task,
            SessionState sessionState) {
        sessionState.cancel();
        task.cancel(true);
        worker.interrupt();
    }

    private static final class SessionState {
        private GitClientTransportSession session;
        private boolean cancelled;

        boolean opened(GitClientTransportSession openedSession) {
            boolean closeImmediately;
            synchronized (this) {
                closeImmediately = cancelled;
                if (!closeImmediately) {
                    session = openedSession;
                }
            }
            if (closeImmediately) {
                closeAfterCancellation(openedSession);
            }
            return !closeImmediately;
        }

        synchronized void closed(GitClientTransportSession closedSession) {
            if (session == closedSession) {
                session = null;
            }
        }

        void cancel() {
            GitClientTransportSession sessionToClose;
            synchronized (this) {
                cancelled = true;
                sessionToClose = session;
                session = null;
            }
            if (sessionToClose != null) {
                closeAfterCancellation(sessionToClose);
            }
        }

        private static void closeAfterCancellation(
                GitClientTransportSession session) {
            try {
                session.close();
            } catch (IOException ignored) {
                // Cancellation already has a primary outcome.
            }
        }
    }

    private static <T> GitClientResult<T> failed(
            GitClientFailure.Kind kind,
            GitClientFailure.Phase phase,
            boolean retryable,
            String message,
            Throwable cause) {
        String checkedMessage = message == null || message.isBlank()
                ? "Remote Git operation failed"
                : message;
        return new GitClientResult.Failed<>(new GitClientFailure(
                kind, phase, retryable, checkedMessage, cause));
    }

    @FunctionalInterface
    interface Operation<T> {
        T run(GitClientTransportSession session)
                throws IOException, GitClientProtocolException;
    }
}
