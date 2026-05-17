package pro.deta.orion.resource.address;

import java.io.IOException;

public class MutableReferenceConflictException extends IOException {
    public MutableReferenceConflictException(String message) {
        super(message);
    }
}
