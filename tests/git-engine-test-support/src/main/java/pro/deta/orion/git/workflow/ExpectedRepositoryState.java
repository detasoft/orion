package pro.deta.orion.git.workflow;

import java.util.List;
import java.util.Map;

public record ExpectedRepositoryState(
        String headSymref,
        Map<String, String> refs,
        Map<String, ExpectedCommit> commits) {
    public ExpectedRepositoryState {
        refs = Map.copyOf(refs);
        commits = Map.copyOf(commits);
    }

    public static ExpectedRepositoryState unspecified() {
        return new ExpectedRepositoryState("", Map.of(), Map.of());
    }

    public record ExpectedCommit(List<String> parents, Map<String, ExpectedFile> files) {
        public ExpectedCommit {
            parents = List.copyOf(parents);
            files = Map.copyOf(files);
        }
    }

    public record ExpectedFile(int mode, String contentHash) {
    }
}
