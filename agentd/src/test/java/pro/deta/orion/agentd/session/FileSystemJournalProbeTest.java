package pro.deta.orion.agentd.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemJournalProbeTest {
    private static final SessionEventCodec CODEC =
            new SessionEventCodec(AgentProtocolLimits.journalDefaults());

    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsTheCompleteRangeWithoutCountingAPartialActiveTail() throws Exception {
        Path journal = temporaryDirectory.resolve("00000001.cbor");
        byte[] first = event(4);
        byte[] last = event(9);
        byte[] partial = event(12);
        Files.write(journal, concatenate(first, last, partial, partial.length - 1));

        JournalObservation observation = new FileSystemJournalProbe().probe(temporaryDirectory);

        assertThat(observation.readable()).isTrue();
        assertThat(observation.firstAvailableEventId()).contains(new EventId(4));
        assertThat(observation.lastAvailableEventId()).contains(new EventId(9));
    }

    @Test
    void reportsAnInitializedEmptyJournalWithoutInventingARange() throws Exception {
        Files.createFile(temporaryDirectory.resolve("00000001.cbor"));

        JournalObservation observation = new FileSystemJournalProbe().probe(temporaryDirectory);

        assertThat(observation.readable()).isTrue();
        assertThat(observation.firstAvailableEventId()).isEmpty();
        assertThat(observation.lastAvailableEventId()).isEmpty();
    }

    @Test
    void readsTheRangeAcrossBoundedPages() throws Exception {
        ByteArrayOutputStream journal = new ByteArrayOutputStream();
        for (int eventId = 1; eventId <= 257; eventId++) {
            journal.write(event(eventId));
        }
        Files.write(temporaryDirectory.resolve("00000001.cbor"), journal.toByteArray());

        JournalObservation observation = new FileSystemJournalProbe().probe(temporaryDirectory);

        assertThat(observation.firstAvailableEventId()).contains(new EventId(1));
        assertThat(observation.lastAvailableEventId()).contains(new EventId(257));
    }

    private static byte[] event(long eventId) throws Exception {
        return CODEC.encode(
                new EventId(eventId),
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(new byte[]{(byte) eventId})));
    }

    private static byte[] concatenate(byte[] first, byte[] second, byte[] third, int thirdLength) {
        byte[] combined = new byte[first.length + second.length + thirdLength];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        System.arraycopy(third, 0, combined, first.length + second.length, thirdLength);
        return combined;
    }
}
