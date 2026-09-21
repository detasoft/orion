package pro.deta.orion.transport.http;

/** An expected request validation failure whose message is safe to return to the client. */
final class HttpRequestValidationException extends IllegalArgumentException {
    HttpRequestValidationException(String message) {
        super(message);
    }
}
