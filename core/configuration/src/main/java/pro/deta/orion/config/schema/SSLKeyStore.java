package pro.deta.orion.config.schema;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
public class SSLKeyStore {
    private String path;
    private String keyPassword;
    private SSLKeyStoreType type = SSLKeyStoreType.PEM;
    private String keyStorePassword = null;
    private String alias = null;

    public enum SSLKeyStoreType  {
        PEM, JKS, PKCS_12;
    }
}
