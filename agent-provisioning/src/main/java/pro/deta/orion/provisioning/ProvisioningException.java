package pro.deta.orion.provisioning;

public final class ProvisioningException extends Exception {
    private final ProvisioningFailure failure;

    ProvisioningException(ProvisioningFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    ProvisioningException(ProvisioningFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public ProvisioningFailure failure() {
        return failure;
    }
}
