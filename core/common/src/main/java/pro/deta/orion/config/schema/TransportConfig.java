package pro.deta.orion.config.schema;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class TransportConfig {
    private final String defaultAddress = "localhost";
    private String address = null;
    private int port = 9418;
    private int backlog = 10;
    private boolean enabled = true;

    public TransportConfig(String address, int port) {
        this.address = address;
        this.port = port;
    }

    public String getAddress() {
        if (address == null) {
            return defaultAddress;
        }
        return address;
    }
}
