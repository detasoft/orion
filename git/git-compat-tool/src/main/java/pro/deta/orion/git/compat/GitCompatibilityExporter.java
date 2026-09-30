package pro.deta.orion.git.compat;

import java.io.IOException;
import java.nio.file.Path;

/** Exports a quiescent Orion repository as a new, verified bare Git repository. */
public interface GitCompatibilityExporter {
    void export(Path sourceStore, String repositoryName, Path bareOutput) throws IOException;
}
