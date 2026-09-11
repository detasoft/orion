package pro.deta.orion.schema.config;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Data
@NoArgsConstructor
public class SshTransportConfig extends TransportConfig {
    private List<SshHostKeyReferenceConfig> hostKeys = new ArrayList<>();

    public SshTransportConfig(String address, int port) {
        super(address, port);
    }
}
