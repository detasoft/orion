package pro.deta.orion.agentd.session;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface JournalProbe {
    JournalObservation probe(Path sessionDirectory) throws IOException;
}
