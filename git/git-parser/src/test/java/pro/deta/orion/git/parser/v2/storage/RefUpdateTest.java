package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RefUpdateTest {
    private static final RefId REF = new RefId("refs/heads/main");
    private static final ObjectId OLD = new ObjectId("1".repeat(40));
    private static final ObjectId NEW = new ObjectId("2".repeat(40));

    @Test
    void representsCreationReplacementAndDeletion() {
        RefUpdate creation = new RefUpdate(REF, Optional.empty(), Optional.of(NEW));
        assertEquals(REF, creation.ref());
        assertTrue(creation.expectedOld().isEmpty());
        assertEquals(Optional.of(NEW), creation.newId());

        RefUpdate replacement = new RefUpdate(REF, Optional.of(OLD), Optional.of(NEW));
        assertEquals(REF, replacement.ref());
        assertEquals(Optional.of(OLD), replacement.expectedOld());
        assertEquals(Optional.of(NEW), replacement.newId());

        RefUpdate deletion = new RefUpdate(REF, Optional.of(OLD), Optional.empty());
        assertEquals(REF, deletion.ref());
        assertEquals(Optional.of(OLD), deletion.expectedOld());
        assertTrue(deletion.newId().isEmpty());
    }

    @Test
    void rejectsAnUpdateWithNeitherExpectedNorNewValue() {
        assertThrows(IllegalArgumentException.class,
                () -> new RefUpdate(REF, Optional.empty(), Optional.empty()));
    }

    @Test
    void rejectsNullRefAndOptionalContainers() {
        assertThrows(NullPointerException.class,
                () -> new RefUpdate(null, Optional.of(OLD), Optional.of(NEW)));
        assertThrows(NullPointerException.class,
                () -> new RefUpdate(REF, null, Optional.of(NEW)));
        assertThrows(NullPointerException.class,
                () -> new RefUpdate(REF, Optional.of(OLD), null));
    }
}
