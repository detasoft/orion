package pro.deta.orion.lifecycle.state;

import java.util.Objects;

/**
 * Owns one current phase whose event handler returns the next phase. Phase
 * implementations own their durable state and resources, transfer or release
 * them during transitions, and expose whether they are terminal. The machine
 * closes only the current phase, rejects events after a terminal phase or
 * close, and does not provide synchronization or an execution policy.
 */
public final class PhaseMachine<E, P extends PhaseMachine.Phase<E, P>> implements AutoCloseable {
    private P phase;
    private boolean closed;

    public PhaseMachine(P initialPhase) {
        phase = Objects.requireNonNull(initialPhase, "initialPhase");
    }

    public P phase() {
        return phase;
    }

    public P accept(E event) {
        Objects.requireNonNull(event, "event");
        if (closed) {
            throw new IllegalStateException("Phase machine is closed");
        }
        if (phase.terminal()) {
            throw new IllegalStateException("Terminal phase does not accept events");
        }
        P nextPhase = Objects.requireNonNull(phase.accept(event), "nextPhase");
        phase = nextPhase;
        return nextPhase;
    }

    public boolean terminal() {
        return phase.terminal();
    }

    public boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        phase.close();
    }

    public interface Phase<E, P extends Phase<E, P>> extends AutoCloseable {
        P accept(E event);

        default boolean terminal() {
            return false;
        }

        @Override
        default void close() {
        }
    }
}
