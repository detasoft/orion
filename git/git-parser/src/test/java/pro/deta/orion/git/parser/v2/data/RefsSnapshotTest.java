package pro.deta.orion.git.parser.v2.data;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.id.CommitId;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RefsSnapshotTest {
    private static final RefId MAIN = new RefId("refs/heads/main");
    private static final ObjectId OBJECT = new ObjectId("1".repeat(40));

    @Test
    void retainsBranchIdentityWhenSeveralRefsPointToTheSameObject() {
        RefId release = new RefId("refs/heads/release");
        Head.Symbolic head = new Head.Symbolic(MAIN);
        RefsSnapshot snapshot = new RefsSnapshot(Map.of(MAIN, OBJECT, release, OBJECT), head);

        assertEquals(head, snapshot.head());
        assertEquals(OBJECT, snapshot.refs().get(head.target()));
        assertNotEquals(new Head.Symbolic(release), snapshot.head());
    }

    @Test
    void supportsAnUnbornBranchAndADetachedCommitWithoutRefs() {
        Head.Symbolic symbolic = new Head.Symbolic(MAIN);
        RefsSnapshot unborn = new RefsSnapshot(Map.of(), symbolic);
        assertEquals(symbolic, unborn.head());
        assertFalse(unborn.refs().containsKey(symbolic.target()));

        CommitId commit = new CommitId(OBJECT.toBytes());
        Head.Detached detached = new Head.Detached(commit);
        RefsSnapshot snapshot = new RefsSnapshot(Map.of(), detached);
        assertEquals(detached, snapshot.head());
        assertEquals(commit, detached.target());
    }

    @Test
    void ownsAnImmutableSnapshotOfTheRefMap() {
        Map<RefId, ObjectId> refs = new HashMap<>();
        refs.put(MAIN, OBJECT);
        RefsSnapshot snapshot = new RefsSnapshot(refs, new Head.Symbolic(MAIN));
        refs.clear();

        assertEquals(Map.of(MAIN, OBJECT), snapshot.refs());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.refs().clear());
    }

    @Test
    void rejectsNullHeadTargetsAndSnapshotComponents() {
        assertThrows(NullPointerException.class, () -> new Head.Symbolic(null));
        assertThrows(NullPointerException.class, () -> new Head.Detached(null));
        assertThrows(NullPointerException.class, () -> new RefsSnapshot(null, new Head.Symbolic(MAIN)));
        assertThrows(NullPointerException.class, () -> new RefsSnapshot(Map.of(), null));
    }
}
