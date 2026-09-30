package pro.deta.orion.git.parser.v2.index;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.RefId;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Opens independent accesses with a fixed set of writable refs and their original values.
 * Names capture current values without preparing changes; complete updates validate expected values
 * and prepare their targets immediately. An access opened without refs can only modify objects and HEAD.
 */
public interface GitIndexApi {
    default GitIndexAccess createAccess() throws IOException {
        return createAccess(Set.of());
    }

    GitIndexAccess createAccess(Set<RefId> refs) throws IOException;

    GitIndexAccess createAccess(List<RefUpdate> updates) throws IOException;

    GitHashAlgorithm hashAlgorithm();
}
