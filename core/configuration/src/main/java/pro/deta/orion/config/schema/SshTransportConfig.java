package pro.deta.orion.config.schema;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Data
public class SshTransportConfig extends TransportConfig {
    public SshTransportConfig(String address, int port) {
        super(address, port);
    }
}
