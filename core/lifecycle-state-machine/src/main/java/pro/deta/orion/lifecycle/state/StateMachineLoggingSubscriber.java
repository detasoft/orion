package pro.deta.orion.lifecycle.state;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

public final class StateMachineLoggingSubscriber implements StateMachineEventSubscriber {
    private final String owner;
    private final Consumer<String> logger;
    private Instant transitionStartedAt;
    private Instant functionStartedAt;
    private StateMachineEvent lastStartedTransition;

    public StateMachineLoggingSubscriber(String owner, Consumer<String> logger) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void onEvent(StateMachineEvent event) {
        Objects.requireNonNull(event, "event");
        Instant observedAt = Instant.now();
        switch (event.type()) {
            case TRANSITION_STARTED -> onTransitionStarted(event, observedAt);
            case TRANSITION_FUNCTION_STARTED -> onFunctionStarted(event, observedAt);
            case TRANSITION_FUNCTION_FINISHED -> onFunctionFinished(event, observedAt);
            case STATE_ENTERED -> onStateEntered(event, observedAt);
            case TRANSITION_FINISHED -> onTransitionFinished(event, observedAt);
            case TRANSITION_FAILED -> onTransitionFailed(event, observedAt);
        }
    }

    private void onTransitionStarted(StateMachineEvent event, Instant observedAt) {
        transitionStartedAt = observedAt;
        lastStartedTransition = event;
        log(observedAt, "transition started: " + describe(event));
    }

    private void onFunctionStarted(StateMachineEvent event, Instant observedAt) {
        functionStartedAt = observedAt;
        log(observedAt, "transition function started: " + describe(event));
    }

    private void onFunctionFinished(StateMachineEvent event, Instant observedAt) {
        Duration duration = Duration.ZERO;
        if (functionStartedAt != null) {
            duration = Duration.between(functionStartedAt, observedAt);
        }
        log(observedAt, "transition function finished: " + describe(event) + " in " + duration);
    }

    private void onStateEntered(StateMachineEvent event, Instant observedAt) {
        Duration duration = Duration.ZERO;
        if (transitionStartedAt != null) {
            duration = Duration.between(transitionStartedAt, observedAt);
        }
        log(observedAt, "state entered: " + event.currentState() + " in " + duration);
    }

    private void onTransitionFinished(StateMachineEvent event, Instant observedAt) {
        Duration duration = Duration.ZERO;
        if (transitionStartedAt != null) {
            duration = Duration.between(transitionStartedAt, observedAt);
        }
        log(observedAt, "transition finished: " + describe(event) + " in " + duration);
        clearTransition();
    }

    private void onTransitionFailed(StateMachineEvent event, Instant observedAt) {
        Duration duration = Duration.ZERO;
        if (transitionStartedAt != null) {
            duration = Duration.between(transitionStartedAt, observedAt);
        }
        log(observedAt, "transition failed: " + describe(event) + " in " + duration + " cause=" + event.failure());
        clearTransition();
    }

    private void clearTransition() {
        transitionStartedAt = null;
        functionStartedAt = null;
        lastStartedTransition = null;
    }

    private String describe(StateMachineEvent event) {
        StateMachineEvent source = lastStartedTransition;
        if (source == null) {
            source = event;
        }
        return source.from() + " --" + source.action().id() + "--> " + source.targetState();
    }

    private void log(Instant observedAt, String message) {
        logger.accept(owner + " " + observedAt + " " + message);
    }
}
