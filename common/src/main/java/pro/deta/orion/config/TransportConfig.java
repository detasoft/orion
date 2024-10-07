package pro.deta.orion.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class TransportConfig {
    private String address = "localhost";
    private int port = 9418;
}
