package pro.deta.orion.provisioning;

import java.time.Duration;

public record AgentdRecoveryOptions(
        Duration offlineTimeout,
        Duration startupTimeout,
        Duration terminationGrace,
        Duration killConfirmationTimeout,
        Duration initialBackoff,
        Duration maximumBackoff,
        int maximumAttempts
) {
    public AgentdRecoveryOptions {
        requirePositive(offlineTimeout, "offline timeout");
        requirePositive(startupTimeout, "startup timeout");
        requirePositive(terminationGrace, "termination grace");
        requirePositive(killConfirmationTimeout, "kill confirmation timeout");
        requirePositive(initialBackoff, "initial backoff");
        requirePositive(maximumBackoff, "maximum backoff");
        if (maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("AgentD maximum backoff must not be below initial backoff");
        }
        if (maximumAttempts <= 0) {
            throw new IllegalArgumentException("AgentD maximum attempts must be positive");
        }
    }

    private static void requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("AgentD " + label + " must be positive");
        }
    }
}
