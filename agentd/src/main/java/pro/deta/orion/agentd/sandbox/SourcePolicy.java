package pro.deta.orion.agentd.sandbox;

import java.nio.file.Path;
import java.util.List;

public record SourcePolicy(List<Rule> rules) {
    public SourcePolicy {
        rules = List.copyOf(rules);
    }

    public record Rule(Path path, long rights, int sourceLine) {
    }
}
