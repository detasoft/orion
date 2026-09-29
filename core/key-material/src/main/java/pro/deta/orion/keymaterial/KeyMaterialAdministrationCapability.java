package pro.deta.orion.keymaterial;

import java.io.IOException;
import java.security.GeneralSecurityException;

/** Creates named ACME and TLS keys without exposing private material or replacing existing entries. */
public interface KeyMaterialAdministrationCapability {
    void create(String alias, KeyMaterialPurpose purpose, char[] privateKeyPem)
            throws IOException, GeneralSecurityException;

    static KeyMaterialAdministrationCapability unavailable() {
        return (alias, purpose, privateKeyPem) -> {
            if (privateKeyPem != null) java.util.Arrays.fill(privateKeyPem, '\0');
            throw new GeneralSecurityException("Key material administration is unavailable");
        };
    }
}
