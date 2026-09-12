package pro.deta.orion.agent.server.journal;

import org.junit.jupiter.api.Test;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionEventRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalStorageContractTest {
    @Test
    void appendResultOwnsItsNewlyStoredRecords() {
        List<SessionEventRecord> source = new ArrayList<>(List.of(event(1)));
        JournalAppendResult result = new JournalAppendResult(Optional.of(new EventId(1)), source);

        source.clear();

        assertThat(result.newlyStored()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1));
        assertThatThrownBy(() -> result.newlyStored().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void appendResultRejectsNullInputsAndElements() {
        assertThatNullPointerException().isThrownBy(
                () -> new JournalAppendResult(null, List.of()));
        assertThatNullPointerException().isThrownBy(
                () -> new JournalAppendResult(Optional.empty(), null));
        assertThatNullPointerException().isThrownBy(
                () -> new JournalAppendResult(Optional.empty(), Arrays.asList(event(1), null)));
    }

    @Test
    void appendResultAllowsAnEmptyStoredSet() {
        JournalAppendResult result = new JournalAppendResult(Optional.empty(), List.of());
        JournalAppendResult duplicateResult = new JournalAppendResult(Optional.of(new EventId(3)), List.of());

        assertThat(result.durableThrough()).isEmpty();
        assertThat(result.newlyStored()).isEmpty();
        assertThat(duplicateResult.durableThrough()).contains(new EventId(3));
        assertThat(duplicateResult.newlyStored()).isEmpty();
    }

    @Test
    void appendResultRequiresDurableCursorForStoredRecords() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new JournalAppendResult(Optional.empty(), List.of(event(1))));
    }

    @Test
    void appendResultRequiresDurableCursorAtFinalStoredRecord() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new JournalAppendResult(Optional.of(new EventId(2)), List.of(event(1))));
    }

    @Test
    void appendResultRejectsDuplicateEventIds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new JournalAppendResult(
                Optional.of(new EventId(2)),
                List.of(event(2), event(2))));
    }

    @Test
    void appendResultRejectsDecreasingEventIds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new JournalAppendResult(
                Optional.of(new EventId(2)),
                List.of(event(3), event(2))));
    }

    @Test
    void appendResultUsesUnsignedEventOrdering() {
        JournalAppendResult result = new JournalAppendResult(
                Optional.of(new EventId(Long.MIN_VALUE)),
                List.of(event(Long.MAX_VALUE), event(Long.MIN_VALUE)));

        assertThat(result.newlyStored()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(Long.MAX_VALUE), new EventId(Long.MIN_VALUE));
    }

    @Test
    void readResultOwnsItsRecords() {
        List<SessionEventRecord> source = new ArrayList<>(List.of(event(1)));
        JournalReadResult result = new JournalReadResult(source);

        source.clear();

        assertThat(result.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1));
        assertThatThrownBy(() -> result.records().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void readResultRejectsNullInputsAndElements() {
        assertThatNullPointerException().isThrownBy(
                () -> new JournalReadResult(null));
        assertThatNullPointerException().isThrownBy(
                () -> new JournalReadResult(Arrays.asList(event(1), null)));
    }

    @Test
    void readResultAllowsEmptyRecords() {
        JournalReadResult result = new JournalReadResult(List.of());

        assertThat(result.records()).isEmpty();
    }

    @Test
    void readResultRejectsDuplicateEventIds() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new JournalReadResult(List.of(event(2), event(2))));
    }

    @Test
    void readResultRejectsDecreasingEventIds() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new JournalReadResult(List.of(event(3), event(2))));
    }

    @Test
    void readResultUsesUnsignedEventOrdering() {
        JournalReadResult result = new JournalReadResult(
                List.of(event(Long.MAX_VALUE), event(Long.MIN_VALUE)));

        assertThat(result.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(Long.MAX_VALUE), new EventId(Long.MIN_VALUE));
    }

    @Test
    void storageExceptionRetainsReasonAndCause() {
        IOException cause = new IOException("disk failure");

        JournalStorageException failure = new JournalStorageException(
                JournalStorageException.Reason.IO_FAILURE,
                "journal write failed",
                cause);

        assertThat(failure.reason()).isEqualTo(JournalStorageException.Reason.IO_FAILURE);
        assertThat(failure).hasMessage("journal write failed").hasCause(cause);
    }

    @Test
    void storageExceptionRejectsNullReason() {
        assertThatNullPointerException().isThrownBy(
                () -> new JournalStorageException(null, "journal write failed"));
    }

    private static SessionEventRecord event(long id) {
        ProtocolBytes payload = ProtocolBytes.copyOf(new byte[]{1});
        ProtocolBytes record = ProtocolBytes.copyOf(new byte[]{2});
        return new SessionEventRecord(new EventId(id), 1, payload, record, 0);
    }
}
