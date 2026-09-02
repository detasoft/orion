package pro.deta.orion.schema.config;

import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@Data
public class TransportConfig {
    private final String defaultAddress = "localhost";
    private String address = null;
    private String publicUrl = null;
    private int port = 9418;
    private int backlog = 10;
    private boolean enabled = true;

    public TransportConfig(String address, int port) {
        this.address = address;
        this.port = port;
    }

    public TransportConfig(String address, int port, int backlog, boolean enabled) {
        this.address = address;
        this.port = port;
        this.backlog = backlog;
        this.enabled = enabled;
    }

    public String getAddress() {
        if (address == null) {
            return defaultAddress;
        }
        return address;
    }
}
