package pro.deta.orion.storage;

import java.io.IOException;

public class BlobStorageConflictException extends IOException {
    public BlobStorageConflictException(String message) {
        super(message);
    }

    public BlobStorageConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
