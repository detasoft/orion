package pro.deta.orion.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.jgit.util.SystemReader;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;

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
    private final JGitGlobalRuntime globalRuntime;

    @Inject
    public OrionJGitRuntime(ControlledOrionJGitSystemReader systemReader, JGitGlobalRuntime globalRuntime) {
        this.systemReader = Objects.requireNonNull(systemReader, "systemReader");
        this.globalRuntime = Objects.requireNonNull(globalRuntime, "globalRuntime");
    }

    public OrionJGitRuntime(ControlledOrionJGitSystemReader systemReader) {
        this(systemReader, new JGitGlobalRuntime());
    }

    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        registrar.task(this, ApplicationState.INIT, OrionLifecycleTasks.JGIT_RUNTIME, this::install);
        registrar.task(this, ApplicationState.STOPPING, OrionLifecycleTasks.JGIT_RUNTIME_STOP, this::shutdown)
                .after(OrionLifecycleTasks.TRANSPORTS_STOP);
    }

    public OrionStageCallResult install() {
        SystemReader.setInstance(systemReader);
        globalRuntime.initializeGlobalExecutors();
        return OrionStageCallResult.EMPTY;
    }

    public OrionStageCallResult shutdown() {
        globalRuntime.shutdownGlobalExecutors();
        return OrionStageCallResult.EMPTY;
    }
}
