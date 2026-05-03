package pro.deta.orion.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.jgit.util.SystemReader;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;

import java.util.Objects;

/**
 * Owns the process-wide JGit runtime hook used by Orion.
 *
 * <p>JGit exposes {@link SystemReader} as global process state, not as a
 * repository-scoped dependency. Keeping that global write in one lifecycle
 * component makes the boundary explicit: application code depends on
 * {@link ControlledOrionJGitSystemReader}, while this class is the only place
 * that installs it into JGit.</p>
 */
@Singleton
public class OrionJGitRuntime implements OrionApplicationStageEventListener {
    private final ControlledOrionJGitSystemReader systemReader;

    @Inject
    public OrionJGitRuntime(ControlledOrionJGitSystemReader systemReader) {
        this.systemReader = Objects.requireNonNull(systemReader, "systemReader");
    }

    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        registrar.register(ApplicationState.INIT, this::install).priority(-100);
    }

    public OrionStageCallResult install() {
        SystemReader.setInstance(systemReader);
        return OrionStageCallResult.EMPTY;
    }
}
