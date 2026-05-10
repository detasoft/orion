package pro.deta.orion.transport.http;

public class AcmeCertificateIssueException extends RuntimeException {
    public AcmeCertificateIssueException(String message) {
        super(message);
    }

    public AcmeCertificateIssueException(String message, Throwable cause) {
        super(message, cause);
    }
}
