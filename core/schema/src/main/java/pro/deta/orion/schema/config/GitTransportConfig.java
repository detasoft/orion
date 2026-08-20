package pro.deta.orion.schema.config;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Data
public class GitTransportConfig extends TransportConfig {
    public static final int DEFAULT_PORT = 9419;
    private GitPackfileUriConfig packfileUri =
            new GitPackfileUriConfig();

    public GitTransportConfig() {
        this(null, DEFAULT_PORT);
    }

    public GitTransportConfig(String address, int port) {
        super(address, port, 50, true);
    }
}
