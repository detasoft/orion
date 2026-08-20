package pro.deta.orion.git.common;

import java.util.Collection;
import java.util.Map;

public interface GitRefResolver {
    Map<GitObjectId, String> resolveBranchNames(Collection<GitObjectId> objectIds);
}
