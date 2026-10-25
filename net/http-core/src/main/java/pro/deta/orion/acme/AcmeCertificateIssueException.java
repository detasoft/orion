package pro.deta.orion.acme;

public class AcmeCertificateIssueException extends RuntimeException {
    public AcmeCertificateIssueException(String message) {
        super(message);
    }

    public AcmeCertificateIssueException(String message, Throwable cause) {
        super(message, cause);
    }
}
