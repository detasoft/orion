package pro.deta.orion.git;

import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.listener.RegisteredListener;
import pro.deta.orion.lifecycle.task.LifecycleTaskRegistration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static pro.deta.orion.git.JGitRuntimeAssertions.assertControlledJGitSystemReaderInstalled;
import static pro.deta.orion.git.JGitRuntimeAssertions.installDefaultControlledJGitRuntime;

@ResourceLock("jgit-system-reader")
@DisplayName("Orion JGit runtime")
class OrionJGitRuntimeTest {
    @AfterEach
    void resetControlledJGitRuntime() {
        try {
            assertControlledJGitSystemReaderInstalled();
        } finally {
            installDefaultControlledJGitRuntime();
        }
    }

    @Test
    @DisplayName("installs the controlled system reader into JGit")
    void installsControlledSystemReaderIntoJGit() {
        OrionConfiguration.JGitConfig config = new OrionConfiguration.JGitConfig();
        config.getProperties().put("orion.jgit.runtime.test", "controlled");
        ControlledOrionJGitSystemReader controlledReader = new ControlledOrionJGitSystemReader(config);

        new OrionJGitRuntime(controlledReader).install();

        assertControlledJGitSystemReaderInstalled();
        assertThat(SystemReader.getInstance()).isSameAs(controlledReader);
        assertThat(SystemReader.getInstance().getProperty("orion.jgit.runtime.test")).isEqualTo("controlled");
    }

    @Test
    @DisplayName("registers the JGit install hook at the beginning of init")
    void registersJGitInstallHookAtTheBeginningOfInit() {
        RecordingRegistrar registrar = new RecordingRegistrar();
        OrionJGitRuntime runtime = new OrionJGitRuntime(new ControlledOrionJGitSystemReader(new OrionConfiguration.JGitConfig()));

        runtime.registerToStage(registrar);

        assertThat(registrar.listener).isNotNull();
        assertThat(registrar.listener.getState()).isEqualTo(ApplicationState.INIT);
        assertThat(registrar.listener.getPriority()).isEqualTo(-100);
    }

    @Test
    @DisplayName("does not accept a missing system reader")
    void doesNotAcceptMissingSystemReader() {
        assertThatNullPointerException()
                .isThrownBy(() -> new OrionJGitRuntime(null))
                .withMessage("systemReader");
    }

    private static final class RecordingRegistrar implements ApplicationStateListenerRegistrar {
        private RegisteredListener listener;

        @Override
        public RegisteredListener register(RegisteredListener listener) {
            this.listener = listener;
            return listener;
        }

        @Override
        public LifecycleTaskRegistration register(LifecycleTaskRegistration registration) {
            return registration;
        }
    }
}
