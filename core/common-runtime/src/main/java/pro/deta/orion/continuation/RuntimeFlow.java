package pro.deta.orion.continuation;

import java.util.Objects;

/**
 * What {@link ContinuationRuntime#accept(Object)} and {@link
 * ContinuationRuntime#resumeTask()} return to their caller.
 *
 * <h3>Runtime signals</h3>
 * <ul>
 *   <li>{@link ContinuationFlow.Await}/{@link ContinuationFlow.Yield} — passed straight
 *       through from the active continuation's {@link Continuation#process(Object)}
 *       result; see {@link ContinuationFlow} for their meaning.</li>
 *
 *   <li>{@link Terminal} — the current continuation reached a terminal state. The
 *       current continuation itself distinguishes success, error, and timeout — inspect
 *       it to learn which. A {@link Continuation#process(Object)} implementation must
 *       never return this; the runtime synthesizes it after the fact.</li>
 *
 *   <li>{@link Error} — non-terminal: the caller violated the runtime's own API
 *       contract (null input, calling {@link ContinuationRuntime#accept(Object) accept}
 *       while a yield is pending, or using a closed runtime). The current continuation
 *       is unaffected and remains valid.</li>
 * </ul>
 */
public sealed interface RuntimeFlow
        permits
        ContinuationFlow.Await,
        ContinuationFlow.Yield,
        RuntimeFlow.Terminal,
        RuntimeFlow.Error {

    Terminal TERMINAL_INSTANCE = new Terminal();

    static Terminal terminal() {
        return TERMINAL_INSTANCE;
    }

    static Error error(String message, Throwable throwable) {
        return new Error(message, throwable);
    }

    static Error error(String message) {
        return new Error(message, new IllegalStateException(message));
    }

    /**
     * Runtime-only signal that the drive loop stopped because the current continuation
     * reached a terminal state. Never construct or return this from
     * {@link Continuation#process(Object)} — {@link ContinuationRuntime} produces it.
     */
    record Terminal() implements RuntimeFlow {
    }

    /**
     * Non-terminal runtime API contract violation — the caller misused {@link
     * ContinuationRuntime#accept(Object)} or {@link ContinuationRuntime#resumeTask()}.
     * The current continuation is unaffected.
     */
    record Error(String message, Throwable throwable) implements RuntimeFlow {
        public Error {
            if (Objects.requireNonNull(message, "message").isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
            Objects.requireNonNull(throwable, "throwable");
        }
    }
}
