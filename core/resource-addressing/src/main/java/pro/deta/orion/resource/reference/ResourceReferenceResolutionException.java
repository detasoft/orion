package pro.deta.orion.resource.reference;

public class ResourceReferenceResolutionException extends RuntimeException {
    public ResourceReferenceResolutionException(String message) {
        super(message);
    }

    public ResourceReferenceResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
