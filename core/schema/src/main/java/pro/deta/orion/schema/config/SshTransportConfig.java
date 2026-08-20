package pro.deta.orion.schema.config;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Data
@NoArgsConstructor
public class SshTransportConfig extends TransportConfig {
    public SshTransportConfig(String address, int port) {
        super(address, port);
    }
}
