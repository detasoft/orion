package pro.deta.orion.lifecycle.state;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

public final class StateMachineLoggingSubscriber implements StateMachineEventSubscriber {
    private final Class<?> owner;
    private final Consumer<String> logger;
    private Instant transitionStartedAt;
    private Instant functionStartedAt;
    private StateMachineEventPoint lastStartedTransition;

    public StateMachineLoggingSubscriber(Class<?> owner, Consumer<String> logger) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void onEvent(StateMachineEventPoint event) {
        Objects.requireNonNull(event, "event");
        switch (event.type()) {
            case TRANSITION_STARTED -> onTransitionStarted(event);
            case TRANSITION_FUNCTION_STARTED -> onFunctionStarted(event);
            case TRANSITION_FUNCTION_FINISHED -> onFunctionFinished(event);
            case STATE_ENTERED -> log(event, "state entered: " + event.currentState());
            case TRANSITION_FINISHED -> onTransitionFinished(event);
            case TRANSITION_FAILED -> onTransitionFailed(event);
        }
    }

    private void onTransitionStarted(StateMachineEventPoint event) {
        transitionStartedAt = event.occurredAt();
        lastStartedTransition = event;
        log(event, "transition started: " + describe(event));
    }

    private void onFunctionStarted(StateMachineEventPoint event) {
        functionStartedAt = event.occurredAt();
        log(event, "transition function started: " + describe(event));
    }

    private void onFunctionFinished(StateMachineEventPoint event) {
        Duration duration = Duration.ZERO;
        if (functionStartedAt != null) {
            duration = Duration.between(functionStartedAt, event.occurredAt());
        }
        log(event, "transition function finished: " + describe(event) + " in " + duration);
    }

    private void onTransitionFinished(StateMachineEventPoint event) {
        Duration duration = Duration.ZERO;
        if (transitionStartedAt != null) {
            duration = Duration.between(transitionStartedAt, event.occurredAt());
        }
        log(event, "transition finished: " + describe(event) + " in " + duration);
        clearTransition();
    }

    private void onTransitionFailed(StateMachineEventPoint event) {
        Duration duration = Duration.ZERO;
        if (transitionStartedAt != null) {
            duration = Duration.between(transitionStartedAt, event.occurredAt());
        }
        log(event, "transition failed: " + describe(event) + " in " + duration + " cause=" + event.failure());
        clearTransition();
    }

    private void clearTransition() {
        transitionStartedAt = null;
        functionStartedAt = null;
        lastStartedTransition = null;
    }

    private String describe(StateMachineEventPoint event) {
        StateMachineEventPoint source = lastStartedTransition;
        if (source == null) {
            source = event;
        }
        return source.from() + " --" + source.action().id() + "--> " + source.targetState();
    }

    private void log(StateMachineEventPoint event, String message) {
        logger.accept(owner.getName() + " " + event.occurredAt() + " " + message);
    }
}
