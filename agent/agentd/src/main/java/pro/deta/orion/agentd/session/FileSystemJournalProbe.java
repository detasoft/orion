package pro.deta.orion.agentd.session;

import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agentd.journal.FileSystemSessionJournalReader;
import pro.deta.orion.agentd.journal.JournalReadBoundary;
import pro.deta.orion.agentd.journal.JournalReadLimits;
import pro.deta.orion.agentd.journal.JournalReadPage;
import pro.deta.orion.agentd.journal.JournalReadPosition;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

public final class FileSystemJournalProbe implements JournalProbe {
    private static final Pattern SEGMENT = Pattern.compile("[0-9]{8}\\.cbor(?:\\.zst)?");
    private static final JournalReadLimits READ_LIMITS = new JournalReadLimits(
            256, AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES);
    private final FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();

    @Override
    public JournalObservation probe(Path sessionDirectory) throws IOException {
        boolean initialized = false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(sessionDirectory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (SEGMENT.matcher(name).matches()
                        && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    initialized = true;
                }
            }
        }
        if (!initialized) {
            return JournalObservation.MISSING;
        }
        return range(sessionDirectory);
    }

    private JournalObservation range(Path sessionDirectory) throws IOException {
        Optional<EventId> cursor = Optional.empty();
        Optional<JournalReadPosition> position = Optional.empty();
        Optional<EventId> first = Optional.empty();
        Optional<EventId> last = Optional.empty();
        while (true) {
            JournalReadPage page = reader.readPage(sessionDirectory, cursor, position, READ_LIMITS);
            if (page.boundary() == JournalReadBoundary.ISSUE) {
                throw new IOException("session journal is not readable: "
                        + page.issue().orElseThrow().detail());
            }
            if (first.isEmpty()) {
                first = page.firstAvailableEventId();
            }
            if (!page.records().isEmpty()) {
                last = Optional.of(page.records().getLast().eventId());
            }
            if (page.boundary() != JournalReadBoundary.PAGE_LIMIT) {
                return first.isEmpty()
                        ? JournalObservation.READABLE
                        : JournalObservation.readable(first.orElseThrow(), last.orElseThrow());
            }
            cursor = last;
            position = page.nextPosition();
        }
    }
}
