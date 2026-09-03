package pro.deta.orion.provisioning;

import java.time.Duration;

public record ProvisioningOptions(
        Duration connectTimeout,
        Duration authenticationTimeout,
        Duration commandTimeout,
        Duration operationTimeout
) {
    public ProvisioningOptions {
        requirePositive(connectTimeout, "SSH connect timeout");
        requirePositive(authenticationTimeout, "SSH authentication timeout");
        requirePositive(commandTimeout, "SSH command timeout");
        requirePositive(operationTimeout, "Provisioning operation timeout");
    }

    public static ProvisioningOptions defaults() {
        return new ProvisioningOptions(
                Duration.ofSeconds(15),
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2));
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
