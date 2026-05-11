package pro.deta.orion.git;

import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.task.LifecycleTaskDefinition;
import pro.deta.orion.lifecycle.task.LifecycleTaskRegistration;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;

import java.util.ArrayList;
import java.util.List;

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
        RecordingJGitGlobalRuntime globalRuntime = new RecordingJGitGlobalRuntime();

        new OrionJGitRuntime(controlledReader, globalRuntime).install();

        assertControlledJGitSystemReaderInstalled();
        assertThat(SystemReader.getInstance()).isSameAs(controlledReader);
        assertThat(SystemReader.getInstance().getProperty("orion.jgit.runtime.test")).isEqualTo("controlled");
        assertThat(globalRuntime.initializeCalls).isEqualTo(1);
        assertThat(globalRuntime.shutdownCalls).isZero();
    }

    @Test
    @DisplayName("registers the JGit hooks as explicit lifecycle tasks")
    void registersJGitHooksAsExplicitLifecycleTasks() {
        installDefaultControlledJGitRuntime();
        RecordingRegistrar registrar = new RecordingRegistrar();
        OrionJGitRuntime runtime = new OrionJGitRuntime(new ControlledOrionJGitSystemReader(new OrionConfiguration.JGitConfig()));

        runtime.registerToStage(registrar);

        LifecycleTaskDefinition initDefinition = registrar.definition(OrionLifecycleTasks.JGIT_RUNTIME);
        assertThat(initDefinition.phase()).isEqualTo(ApplicationState.INIT);

        LifecycleTaskDefinition stopDefinition = registrar.definition(OrionLifecycleTasks.JGIT_RUNTIME_STOP);
        assertThat(stopDefinition.phase()).isEqualTo(ApplicationState.STOPPING);
        assertThat(stopDefinition.after()).containsExactly(OrionLifecycleTasks.TRANSPORTS_STOP);
    }

    @Test
    @DisplayName("shuts down JGit global executors during lifecycle shutdown")
    void shutsDownJGitGlobalExecutorsDuringLifecycleShutdown() {
        installDefaultControlledJGitRuntime();
        RecordingJGitGlobalRuntime globalRuntime = new RecordingJGitGlobalRuntime();
        OrionJGitRuntime runtime = new OrionJGitRuntime(
                new ControlledOrionJGitSystemReader(new OrionConfiguration.JGitConfig()),
                globalRuntime);

        runtime.shutdown();

        assertThat(globalRuntime.shutdownCalls).isEqualTo(1);
        assertThat(globalRuntime.initializeCalls).isZero();
    }

    @Test
    @DisplayName("does not accept a missing system reader")
    void doesNotAcceptMissingSystemReader() {
        installDefaultControlledJGitRuntime();

        assertThatNullPointerException()
                .isThrownBy(() -> new OrionJGitRuntime(null))
                .withMessage("systemReader");
    }

    @Test
    @DisplayName("does not accept a missing JGit global runtime")
    void doesNotAcceptMissingJGitGlobalRuntime() {
        installDefaultControlledJGitRuntime();

        assertThatNullPointerException()
                .isThrownBy(() -> new OrionJGitRuntime(
                        new ControlledOrionJGitSystemReader(new OrionConfiguration.JGitConfig()),
                        null))
                .withMessage("globalRuntime");
    }

    private static final class RecordingJGitGlobalRuntime extends JGitGlobalRuntime {
        private int initializeCalls;
        private int shutdownCalls;

        @Override
        public void initializeGlobalExecutors() {
            initializeCalls++;
        }

        @Override
        public void shutdownGlobalExecutors() {
            shutdownCalls++;
        }
    }

    private static final class RecordingRegistrar implements ApplicationStateListenerRegistrar {
        private final List<LifecycleTaskRegistration> registrations = new ArrayList<>();

        @Override
        public LifecycleTaskRegistration register(LifecycleTaskRegistration registration) {
            registrations.add(registration);
            return registration;
        }

        private LifecycleTaskDefinition definition(pro.deta.orion.lifecycle.task.LifecycleTaskId id) {
            for (LifecycleTaskRegistration registration : registrations) {
                LifecycleTaskDefinition definition = registration.definition();
                if (definition.id().equals(id)) {
                    return definition;
                }
            }
            throw new AssertionError("Missing lifecycle task " + id);
        }
    }
}
