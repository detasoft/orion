package pro.deta.orion.git.proxy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BootstrapRepositorySources {
    public static final String CONFIGURATION = "configuration";
    public static final String MATERIAL = "material";

    private final Map<String, ResolvedBootstrapSource> resolved;

    public BootstrapRepositorySources(List<ResolvedBootstrapSource> sources) {
        Map<String, ResolvedBootstrapSource> candidate = new LinkedHashMap<>();
        for (ResolvedBootstrapSource source : sources) {
            if (candidate.putIfAbsent(source.sourceId(), source) != null) {
                throw new IllegalArgumentException("Duplicate bootstrap source: " + source.sourceId());
            }
        }
        resolved = Map.copyOf(candidate);
    }

    public ResolvedBootstrapSource required(String sourceId) {
        ResolvedBootstrapSource source = resolved.get(sourceId);
        if (source == null) {
            throw new IllegalStateException("Bootstrap source has not been resolved: " + sourceId);
        }
        return source;
    }

}
