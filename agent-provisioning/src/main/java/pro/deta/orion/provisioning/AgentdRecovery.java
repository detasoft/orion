package pro.deta.orion.provisioning;

import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.time.Duration;

public final class AgentdRecovery {
    public enum State {
        WAITING_OFFLINE,
        RECONCILING,
        TERMINATING_OLD,
        AWAITING_ONLINE,
        RETRY_DELAY,
        ONLINE
    }

    private final AgentdAvailability availability;
    private final AgentdLaunchAttemptSource attempts;
    private final AgentdReplacement replacement;
    private final AgentdRecoveryOptions options;
    private final AgentdSleeper sleeper;
    private volatile State state = State.WAITING_OFFLINE;

    public AgentdRecovery(
            AgentdAvailability availability,
            AgentdLaunchAttemptSource attempts,
            AgentdReplacement replacement,
            AgentdRecoveryOptions options,
            AgentdSleeper sleeper) {
        if (availability == null || attempts == null || replacement == null
                || options == null || sleeper == null) {
            throw new IllegalArgumentException("AgentD recovery arguments must not be null");
        }
        this.availability = availability;
        this.attempts = attempts;
        this.replacement = replacement;
        this.options = options;
        this.sleeper = sleeper;
    }

    public State state() {
        return state;
    }

    public AgentdReplacementResult recover(AgentdProcessIdentity previous)
            throws ProvisioningException, InterruptedException {
        state = State.WAITING_OFFLINE;
        if (!availability.awaitSustainedOffline(options.offlineTimeout())) {
            return null;
        }
        Duration backoff = options.initialBackoff();
        AgentLaunchId priorLaunch = previous == null ? null : previous.launchId();
        long priorGeneration = previous == null ? Long.MIN_VALUE : previous.generation().value();
        AgentdProcessIdentity old = previous;
        ProvisioningException lastFailure = null;
        for (int number = 1; number <= options.maximumAttempts(); number++) {
            AgentdLaunchAttempt attempt = attempts.nextAttempt();
            requireFresh(attempt, priorLaunch, priorGeneration);
            priorLaunch = attempt.request().launchId();
            priorGeneration = attempt.request().generation().value();
            AgentdReplacementResult result;
            try (attempt) {
                state = old == null ? State.RECONCILING : State.TERMINATING_OLD;
                try {
                    result = replacement.reconcile(attempt, old);
                } catch (AgentdPartialLaunchException partial) {
                    old = partial.identity();
                    if (!isRetryable(partial.failure())) {
                        throw partial;
                    }
                    result = recoverPartial(attempt, partial);
                } catch (ProvisioningException failure) {
                    if (!isRetryable(failure.failure())) {
                        throw failure;
                    }
                    lastFailure = failure;
                    result = null;
                }
                if (result != null) {
                    state = State.AWAITING_ONLINE;
                    if (availability.awaitOnline(
                            result.identity().launchId(), options.startupTimeout())) {
                        state = State.ONLINE;
                        return result;
                    }
                    old = result.identity();
                    lastFailure = new ProvisioningException(
                            ProvisioningFailure.STARTUP_TIMEOUT,
                            "AgentD launch " + result.identity().launchId().value()
                                    + " did not become ONLINE within the configured startup timeout");
                }
            }
            if (number == options.maximumAttempts()) {
                throw new ProvisioningException(
                        ProvisioningFailure.RETRIES_EXHAUSTED,
                        exhaustionMessage(number, lastFailure), lastFailure);
            }
            state = State.RETRY_DELAY;
            sleeper.sleep(backoff);
            backoff = doubledAndCapped(backoff, options.maximumBackoff());
        }
        throw new AssertionError("positive AgentD attempt bound exhausted without a result");
    }

    private static void requireFresh(
            AgentdLaunchAttempt attempt,
            AgentLaunchId priorLaunch,
            long priorGeneration) throws ProvisioningException {
        if (attempt == null) {
            throw new ProvisioningException(
                    ProvisioningFailure.RETRIES_EXHAUSTED, "AgentD launch attempt source returned no attempt");
        }
        if ((priorLaunch != null && priorLaunch.equals(attempt.request().launchId()))
                || attempt.request().generation().value() <= priorGeneration) {
            attempt.close();
            throw new ProvisioningException(
                    ProvisioningFailure.UNCERTAIN_IDENTITY,
                    "AgentD retry requires a freshly revoked and reissued launch identity");
        }
    }

    private static Duration doubledAndCapped(Duration value, Duration maximum) {
        if (value.compareTo(maximum) >= 0) {
            return maximum;
        }
        try {
            Duration doubled = value.multipliedBy(2);
            return doubled.compareTo(maximum) > 0 ? maximum : doubled;
        } catch (ArithmeticException overflow) {
            return maximum;
        }
    }

    private static boolean isRetryable(ProvisioningFailure failure) {
        return switch (failure) {
            case CONNECTION, AUTHENTICATION, TRANSFER, INTEGRITY, ACTIVATION,
                    LAUNCH, STARTUP_TIMEOUT, REMOTE_COMMAND, TIMEOUT -> true;
            default -> false;
        };
    }

    private AgentdReplacementResult recoverPartial(
            AgentdLaunchAttempt attempt,
            AgentdPartialLaunchException initial) throws ProvisioningException, InterruptedException {
        AgentdProcessIdentity identity = initial.identity();
        ProvisioningException lastFailure = initial;
        Duration backoff = options.initialBackoff();
        for (int retry = 1; retry <= options.maximumAttempts(); retry++) {
            try {
                AgentdReplacementResult recovered = replacement.recoverPartial(attempt, identity);
                if (recovered == null || !recovered.identity().equals(identity)) {
                    throw new ProvisioningException(
                            ProvisioningFailure.UNCERTAIN_IDENTITY,
                            "Partial recovery returned a different exact AgentD process identity");
                }
                return recovered;
            } catch (AgentdPartialLaunchException partial) {
                if (!partial.identity().equals(identity) || !isRetryable(partial.failure())) {
                    throw partial;
                }
                lastFailure = partial;
            } catch (ProvisioningException failure) {
                if (!isRetryable(failure.failure())) {
                    throw failure;
                }
                lastFailure = failure;
            }
            if (retry < options.maximumAttempts()) {
                state = State.RETRY_DELAY;
                sleeper.sleep(backoff);
                backoff = doubledAndCapped(backoff, options.maximumBackoff());
                state = State.RECONCILING;
            }
        }
        throw new ProvisioningException(
                ProvisioningFailure.UNCERTAIN_IDENTITY,
                "Exact partial AgentD launch could not be reconciled without allocating a fresh identity",
                lastFailure);
    }

    private static String exhaustionMessage(int attempts, ProvisioningException lastFailure) {
        String detail = lastFailure == null ? "unknown failure"
                : lastFailure.failure() + ": " + lastFailure.getMessage();
        if (detail.length() > 512) {
            detail = detail.substring(0, 512);
        }
        return "AgentD startup failed after " + attempts + " attempts; last failure: " + detail;
    }
}
