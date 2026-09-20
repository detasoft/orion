package pro.deta.orion.git.parser.v2.data;

import java.util.Objects;
import java.util.Optional;

/**
 * Couples the original ref update with its storage outcome and optional diagnostic text.
 * The update, status, and message container must be non-null; callers branch on status, not message text.
 * GitStorageApi returns one result per requested update, in request order.
 * For an atomic batch, either all updates are APPLIED or none are; updates prevented by another failure
 * receive ATOMIC_ABORTED. Ref failures do not undo previously published packs.
 */
public record RefUpdateResult(RefUpdate update, Status status, Optional<String> message) {
    public RefUpdateResult {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
    }

    /**
     * Describes storage outcomes: applied, expected-old mismatch, missing new object, storage failure,
     * or cancellation caused by another update's failure in an atomic batch.
     * Access and fast-forward policy checks belong to the calling command.
     */
    public enum Status {
        APPLIED,
        EXPECTED_OLD_MISMATCH,
        OBJECT_NOT_FOUND,
        STORAGE_ERROR,
        REJECTED,
        ATOMIC_ABORTED
    }
}
