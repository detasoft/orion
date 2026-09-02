package pro.deta.orion.agentd.session;

import java.nio.file.Path;
import java.util.Objects;

public record LocalSession(
        Path directory,
        SessionManifest manifest,
        HostObservation host,
        JournalObservation journal,
        LocalSessionState state
) {
    public LocalSession {
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(state, "state");
    }
}
