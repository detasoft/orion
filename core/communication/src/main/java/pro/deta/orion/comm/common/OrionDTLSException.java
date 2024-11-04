package pro.deta.orion.comm.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

@RequiredArgsConstructor
@ToString
@Getter
public class OrionDTLSException extends Exception {
    private final DTLSExceptionCode errorCode;

    public OrionDTLSException(DTLSExceptionCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public OrionDTLSException(DTLSExceptionCode errorCode, Throwable e) {
        super(e.getMessage(), e);
        this.errorCode = errorCode;
    }

    public enum DTLSExceptionCode {
        DTLS_EXCEPTION_HANDSHAKE_GENERAL,
        DTLS_EXCEPTION_HANDSHAKE_STARTED,
        GENERAL,
        DTLS_EXCEPTION_BUFFER_UNDERFLOW,
        DTLS_EXCEPTION_BUFFER_OVERFLOW,
    }
}
