package pro.deta.orion.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.jgit.util.SystemReader;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;

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
public class OrionJGitRuntime implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
    private final ControlledOrionJGitSystemReader systemReader;
    private final JGitGlobalRuntime globalRuntime;
    private volatile boolean running;

    @Inject
    public OrionJGitRuntime(ControlledOrionJGitSystemReader systemReader, JGitGlobalRuntime globalRuntime) {
        this.systemReader = Objects.requireNonNull(systemReader, "systemReader");
        this.globalRuntime = Objects.requireNonNull(globalRuntime, "globalRuntime");
    }

    public OrionJGitRuntime(ControlledOrionJGitSystemReader systemReader) {
        this(systemReader, new JGitGlobalRuntime());
    }

    public void install() {
        SystemReader.setInstance(systemReader);
        globalRuntime.initializeGlobalExecutors();
        running = true;
    }

    public void shutdown() {
        globalRuntime.shutdownGlobalExecutors();
        running = false;
    }

    @Override
    public void onStart() {
        install();
    }

    @Override
    public void onStop() {
        shutdown();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
