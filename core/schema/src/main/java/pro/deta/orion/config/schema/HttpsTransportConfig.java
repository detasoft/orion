package pro.deta.orion.config.schema;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Data
@NoArgsConstructor
public class HttpsTransportConfig extends TransportConfig {
    private SSLKeyStoreConfig ksystore = null;
    private AcmeConfig acme = new AcmeConfig();

    public HttpsTransportConfig(String defaultAddress, int defaultPort) {
        super(defaultAddress, defaultPort);
    }
}
