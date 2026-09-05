package pro.deta.orion.schema.config;

import lombok.Data;

@Data
public class KeyMaterialConfig extends BootstrapSourceConfig {
    private String password = "env:ORION_KEY_MATERIAL_PASSWORD";
    private String clusterId = "orion";
    private ServerSigningConfig serverSigning = new ServerSigningConfig();

    public KeyMaterialConfig() {
        setPath("material.p12");
    }
}
