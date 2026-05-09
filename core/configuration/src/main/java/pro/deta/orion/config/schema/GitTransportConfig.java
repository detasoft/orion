package pro.deta.orion.config.schema;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Data
@NoArgsConstructor
public class GitTransportConfig extends TransportConfig {

    public GitTransportConfig(String address, int port) {
        super(address, port, 50, true);
    }
}
