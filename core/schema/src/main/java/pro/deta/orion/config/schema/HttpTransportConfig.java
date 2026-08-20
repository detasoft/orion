package pro.deta.orion.config.schema;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Data
@NoArgsConstructor
public class HttpTransportConfig extends TransportConfig {
    public HttpTransportConfig(String defaultAddress, int defaultPort) {
        super(defaultAddress, defaultPort);
    }
}
