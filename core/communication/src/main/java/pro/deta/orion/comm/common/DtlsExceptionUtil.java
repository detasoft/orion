package pro.deta.orion.comm.common;

import javax.net.ssl.SSLEngineResult;

import static pro.deta.orion.comm.common.OrionDTLSException.DTLSExceptionCode.*;
import static pro.deta.orion.comm.common.OrionDTLSException.DTLSExceptionCode.GENERAL;

public class DtlsExceptionUtil {
    public static void throwExceptionIfHandshakeFailed(SSLEngineResult.HandshakeStatus from, SSLEngineResult.HandshakeStatus to, SSLEngineResult.HandshakeStatus actual) throws OrionDTLSException {
        throw new OrionDTLSException(DTLS_EXCEPTION_HANDSHAKE_GENERAL, "Handshake status not changed " + from + " -> " + to + " (actual: " + actual + ")");
    }

    public static void throwExceptionIfHandshakeFailed(String message) throws OrionDTLSException {
        throw new OrionDTLSException(DTLS_EXCEPTION_HANDSHAKE_GENERAL, message);
    }

    public static void throwExceptionBufferUnderflow() throws OrionDTLSException {
        throw new OrionDTLSException(DTLS_EXCEPTION_BUFFER_UNDERFLOW);
    }
    public static void throwExceptionBufferOverflow() throws OrionDTLSException {
        throw new OrionDTLSException(DTLS_EXCEPTION_BUFFER_OVERFLOW);
    }

    public static void throwSslException(String message) throws OrionDTLSException {
        throw new OrionDTLSException(GENERAL, message);
    }
    public static void throwSslException(Throwable t) throws OrionDTLSException {
        throw new OrionDTLSException(GENERAL, t);
    }
}
