package pro.deta.orion.agentd.sandbox;

import java.nio.file.Path;
import java.util.List;

public record CompiledPolicy(long handledRights, List<Rule> rules) {
    public static final long VERSION = 1;

    public CompiledPolicy {
        rules = List.copyOf(rules);
    }

    public record Rule(Path path, long rights) {
    }
}
