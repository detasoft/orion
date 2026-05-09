package pro.deta.orion.crypto;

import java.security.PublicKey;
import java.util.Collection;
import java.util.Collections;

public interface PublicKeysProvider {
    PublicKeysProvider DEFAULT = new PublicKeysProvider() {
    };

    default Collection<PublicKey> getPublicKeys() {
        return Collections.emptyList();
    }
}
