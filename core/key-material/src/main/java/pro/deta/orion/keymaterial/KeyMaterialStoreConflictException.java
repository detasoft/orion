package pro.deta.orion.keymaterial;

import java.io.IOException;

public class KeyMaterialStoreConflictException extends IOException {
    public KeyMaterialStoreConflictException(String message) {
        super(message);
    }

    public KeyMaterialStoreConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
