package pro.deta.orion.schema.config;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SSLKeyStoreConfig {
    private String path;
    private String keyPassword;
    private SSLKeyStoreType type = SSLKeyStoreType.PEM;
    private String keyStorePassword = null;
    private String alias = null;

    public enum SSLKeyStoreType  {
        PEM, JKS, PKCS_12;
    }
}
