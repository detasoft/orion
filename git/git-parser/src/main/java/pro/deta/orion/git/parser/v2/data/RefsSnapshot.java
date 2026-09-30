package pro.deta.orion.git.parser.v2.data;

import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;

import java.util.Map;
import java.util.Objects;

/**
 * Holds refs and HEAD read together through a GitIndexAccess, including its captured and pending refs.
 * Owns an immutable copy of the ref map; HEAD is represented separately by head.
 * A symbolic HEAD may target a ref absent from the map, including in an empty repository.
 * Constructing this value does not read storage or establish consistency between independently read values.
 */
public record RefsSnapshot(Map<RefId, ObjectId> refs, Head head) {
    public RefsSnapshot {
        refs = Map.copyOf(refs);
        Objects.requireNonNull(head, "head");
    }
}
