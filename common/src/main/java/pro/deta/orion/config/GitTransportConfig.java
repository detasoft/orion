package pro.deta.orion.config;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Data
public class GitTransportConfig extends TransportConfig {

    public GitTransportConfig() {
        super("localhost", 9418);
    }
}
