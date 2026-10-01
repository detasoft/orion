package pro.deta.orion.provisioning;

public class ProvisioningException extends Exception {
    private final ProvisioningFailure failure;

    public ProvisioningException(ProvisioningFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public ProvisioningException(ProvisioningFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public ProvisioningFailure failure() {
        return failure;
    }
}
