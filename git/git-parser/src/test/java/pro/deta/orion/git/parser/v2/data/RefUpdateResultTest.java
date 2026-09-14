package pro.deta.orion.git.parser.v2.data;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static pro.deta.orion.git.parser.v2.data.RefUpdateResult.Status.*;

class RefUpdateResultTest {
    private static final RefUpdate UPDATE = new RefUpdate(new RefId("refs/heads/main"),
            Optional.of(new ObjectId("1".repeat(40))), Optional.of(new ObjectId("2".repeat(40))));

    @Test
    void retainsTheAttemptedUpdateOnSuccessAndFailure() {
        RefUpdateResult applied = new RefUpdateResult(UPDATE, APPLIED, Optional.empty());
        assertSame(UPDATE, applied.update());
        assertEquals(APPLIED, applied.status());
        assertTrue(applied.message().isEmpty());

        RefUpdateResult rejected = new RefUpdateResult(UPDATE, EXPECTED_OLD_MISMATCH,
                Optional.of("Ref changed since it was read"));
        assertSame(UPDATE, rejected.update());
        assertEquals(EXPECTED_OLD_MISMATCH, rejected.status());
        assertEquals(Optional.of("Ref changed since it was read"), rejected.message());
    }

    @Test
    void rejectsNullUpdateStatusAndMessageContainer() {
        assertThrows(NullPointerException.class,
                () -> new RefUpdateResult(null, APPLIED, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> new RefUpdateResult(UPDATE, null, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> new RefUpdateResult(UPDATE, APPLIED, null));
    }
}
