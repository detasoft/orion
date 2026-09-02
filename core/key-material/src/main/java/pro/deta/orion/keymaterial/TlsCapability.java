package pro.deta.orion.keymaterial;

import javax.net.ssl.SSLContext;
import java.security.GeneralSecurityException;

public interface TlsCapability {
    KeyMaterialDescriptor descriptor();

    SSLContext createContext() throws GeneralSecurityException;
}
