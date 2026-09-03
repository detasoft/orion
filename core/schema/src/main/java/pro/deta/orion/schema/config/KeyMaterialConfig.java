package pro.deta.orion.schema.config;

import lombok.Data;

@Data
public class KeyMaterialConfig {
    private String location = "key-material/orion.p12";
    private String password = "env:ORION_KEY_MATERIAL_PASSWORD";
    private boolean createIfMissing = true;
    private String clusterId = "orion";
    private ServerSigningConfig serverSigning = new ServerSigningConfig();
}
