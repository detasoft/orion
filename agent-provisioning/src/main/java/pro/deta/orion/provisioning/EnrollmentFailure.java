package pro.deta.orion.provisioning;

public enum EnrollmentFailure {
    CONNECTION,
    HOST_IDENTITY,
    AUTHENTICATION,
    BOOTSTRAP_PASSWORD_REQUIRED,
    KEY_MATERIAL,
    UNSAFE_REMOTE_STATE,
    REMOTE_WRITE,
    VERIFICATION,
    TIMEOUT
}
