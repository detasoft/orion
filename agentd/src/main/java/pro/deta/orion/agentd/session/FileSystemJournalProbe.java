package pro.deta.orion.agentd.session;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

public final class FileSystemJournalProbe implements JournalProbe {
    private static final Pattern SEGMENT = Pattern.compile("[0-9]{8}\\.cbor(?:\\.zst)?");

    @Override
    public JournalObservation probe(Path sessionDirectory) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(sessionDirectory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (SEGMENT.matcher(name).matches()
                        && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    try (FileChannel ignored = FileChannel.open(entry, StandardOpenOption.READ)) {
                        return JournalObservation.READABLE;
                    }
                }
            }
        }
        return JournalObservation.MISSING;
    }
}
