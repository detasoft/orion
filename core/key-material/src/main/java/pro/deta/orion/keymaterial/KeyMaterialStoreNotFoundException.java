package pro.deta.orion.keymaterial;

import java.io.IOException;

public class KeyMaterialStoreNotFoundException extends IOException {
    public KeyMaterialStoreNotFoundException(String message) {
        super(message);
    }
}
