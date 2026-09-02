package pro.deta.orion.agentd.session;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface HostProbe {
    HostObservation probe(Path sessionDirectory, SessionManifest manifest) throws IOException;
}
