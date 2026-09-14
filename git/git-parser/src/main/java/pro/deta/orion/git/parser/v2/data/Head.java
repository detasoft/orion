package pro.deta.orion.git.parser.v2.data;

import pro.deta.orion.git.parser.v2.id.CommitId;
import pro.deta.orion.git.parser.v2.id.RefId;

import java.util.Objects;

/**
 * Represents HEAD as either a symbolic ref target or a detached commit target.
 * Symbolic targets are resolved against the refs in the same RefsSnapshot.
 */
public sealed interface Head {
    /**
     * Keeps the target ref name, including an unborn branch that has no entry in the snapshot yet.
     */
    record Symbolic(RefId target) implements Head {
        public Symbolic {
            Objects.requireNonNull(target, "target");
        }
    }

    /**
     * Points directly to a commit independently of the refs in the snapshot.
     */
    record Detached(CommitId target) implements Head {
        public Detached {
            Objects.requireNonNull(target, "target");
        }
    }
}
