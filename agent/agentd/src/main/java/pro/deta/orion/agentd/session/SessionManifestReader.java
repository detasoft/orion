package pro.deta.orion.agentd.session;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface SessionManifestReader {
    SessionManifest read(Path sessionDirectory) throws IOException;
}
