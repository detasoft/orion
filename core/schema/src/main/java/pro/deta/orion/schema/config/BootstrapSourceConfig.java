package pro.deta.orion.schema.config;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class BootstrapSourceConfig {
    private String location = "local:orion";
    private String ref = "refs/heads/main";
    private String path;
    private Map<String, String> auth = new LinkedHashMap<>();

    public String selectedRef() {
        if (ref == null || ref.isBlank()) {
            throw new IllegalStateException("Bootstrap source ref must not be empty");
        }
        return ref;
    }
}
