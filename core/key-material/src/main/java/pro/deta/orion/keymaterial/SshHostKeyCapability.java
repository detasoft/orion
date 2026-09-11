package pro.deta.orion.keymaterial;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.List;

public interface SshHostKeyCapability {
    List<KeyMaterialDescriptor> descriptors();

    List<KeyPair> keyPairs() throws GeneralSecurityException;

    static SshHostKeyCapability unavailable() {
        return new SshHostKeyCapability() {
            @Override
            public List<KeyMaterialDescriptor> descriptors() {
                throw unavailableFailure();
            }

            @Override
            public List<KeyPair> keyPairs() {
                throw unavailableFailure();
            }

            private IllegalStateException unavailableFailure() {
                return new IllegalStateException("SSH host key material is not available");
            }
        };
    }
}
