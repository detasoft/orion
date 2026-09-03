package pro.deta.orion.git.sync;

import java.util.Optional;

public interface GitCommitRelationships {
    boolean isAncestor(String ancestor, String descendant);

    Optional<String> mergeBase(String first, String second);
}
