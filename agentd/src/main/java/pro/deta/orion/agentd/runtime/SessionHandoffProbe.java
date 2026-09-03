package pro.deta.orion.agentd.runtime;

import pro.deta.orion.agentd.session.HostObservation;
import pro.deta.orion.agentd.session.OperationDeadline;
import pro.deta.orion.agentd.session.SessionManifest;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface SessionHandoffProbe {
    HostObservation probe(
            Path sessionDirectory,
            SessionManifest manifest,
            OperationDeadline deadline
    ) throws IOException;
}
