package pro.deta.orion.schema.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ServerSigningConfig {
    private String algorithm = "RSA";
    private SigningKeyReferenceConfig active = new SigningKeyReferenceConfig("server-signing-v1", 1);
    private List<SigningKeyReferenceConfig> verification = new ArrayList<>();
}
