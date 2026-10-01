package pro.deta.orion.provisioning;

public final class SshKeyEnrollmentException extends Exception {
    private final EnrollmentFailure failure;

    SshKeyEnrollmentException(EnrollmentFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public EnrollmentFailure failure() {
        return failure;
    }
}
