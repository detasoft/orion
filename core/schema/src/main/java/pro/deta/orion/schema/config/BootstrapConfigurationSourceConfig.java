package pro.deta.orion.schema.config;

import lombok.Data;

@Data
public class BootstrapConfigurationSourceConfig extends BootstrapSourceConfig {
    private boolean createDefaultIfMissing = true;
    private String branch;

    public BootstrapConfigurationSourceConfig() {
        super.setPath("orion.xml");
    }

    @Override
    public String selectedRef() {
        return branch == null || branch.isBlank() ? super.selectedRef() : branch;
    }

    public String configurationRef() {
        return selectedRef();
    }
}
