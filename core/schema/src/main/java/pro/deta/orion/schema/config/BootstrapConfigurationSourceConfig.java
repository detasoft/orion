package pro.deta.orion.schema.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BootstrapConfigurationSourceConfig extends BootstrapSourceConfig {
    private boolean createDefaultIfMissing = true;
    private String branch;
    private List<String> paths = new ArrayList<>(List.of("orion.xml"));

    public BootstrapConfigurationSourceConfig() {
        super.setPath("orion.xml");
    }

    @Override
    public String selectedRef() {
        return branch == null || branch.isBlank() ? super.selectedRef() : branch;
    }

    public List<String> selectedPaths() {
        if (paths == null || paths.isEmpty()) {
            throw new IllegalStateException("At least one ACL path must be configured");
        }
        return List.copyOf(paths);
    }

    public String primaryPath() {
        return selectedPaths().getFirst();
    }

    public String configurationRef() {
        return selectedRef();
    }

    @Override
    public void setPath(String path) {
        super.setPath(path);
        paths = path == null ? null : new ArrayList<>(List.of(path));
    }

    public void setPaths(List<String> paths) {
        this.paths = paths == null ? null : new ArrayList<>(paths);
        if (paths != null && !paths.isEmpty()) {
            super.setPath(paths.getFirst());
        }
    }
}
