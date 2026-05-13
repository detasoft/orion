package pro.deta.orion.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.internal.WorkQueue;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.task.LifecycleTaskDefinition;
import pro.deta.orion.lifecycle.task.LifecycleTaskRegistration;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static pro.deta.orion.git.JGitRuntimeAssertions.assertControlledJGitSystemReaderInstalled;
import static pro.deta.orion.git.JGitRuntimeAssertions.installDefaultControlledJGitRuntime;

@ResourceLock("jgit-system-reader")
@DisplayName("Orion JGit runtime")
class OrionJGitRuntimeTest {
    private static final String BRANCH = "master";

    @TempDir
    private Path tempDir;

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
    @DisplayName("keeps JGit global executors usable after lifecycle shutdown")
    void keepsJGitGlobalExecutorsUsableAfterLifecycleShutdown() throws Exception {
        installDefaultControlledJGitRuntime();
        JGitGlobalRuntime globalRuntime = new JGitGlobalRuntime();

        globalRuntime.initializeGlobalExecutors();
        pushToBareRepository(tempDir.resolve("before-shutdown"));
        globalRuntime.shutdownGlobalExecutors();

        assertThatCode(() -> {
            globalRuntime.initializeGlobalExecutors();
            pushToBareRepository(tempDir.resolve("after-restart"));
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("does not stop JGit WorkQueue during lifecycle shutdown")
    void doesNotStopJGitWorkQueueDuringLifecycleShutdown() {
        installDefaultControlledJGitRuntime();
        JGitGlobalRuntime globalRuntime = new JGitGlobalRuntime();
        ScheduledThreadPoolExecutor workQueue = WorkQueue.getExecutor();

        globalRuntime.initializeGlobalExecutors();
        globalRuntime.shutdownGlobalExecutors();

        assertThat(WorkQueue.getExecutor()).isSameAs(workQueue);
        assertThat(workQueue.isShutdown()).isFalse();
        assertThat(workQueue.isTerminated()).isFalse();
    }

    @Test
    @DisplayName("creates JGit helper threads outside the Orion executor thread group")
    void createsJGitHelperThreadsOutsideOrionExecutorThreadGroup() throws Exception {
        OrionExecutor executor = new OrionExecutor(2, new OrionThreadFactory());
        try {
            Thread jgitThread = executor.submit(() -> {
                ThreadFactory factory = JGitGlobalRuntime.daemonThreadFactory("JGit-Test");
                return factory.newThread(() -> {
                });
            }).get(1, TimeUnit.SECONDS);

            assertThat(jgitThread.isDaemon()).isTrue();
            assertThat(jgitThread.getContextClassLoader()).isNull();
            assertThat(hasThreadGroupNamed(jgitThread, "OrionExecutor")).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("initializes JGit WorkQueue outside the Orion executor thread group")
    void initializesJGitWorkQueueOutsideOrionExecutorThreadGroup() throws Exception {
        OrionExecutor executor = new OrionExecutor(2, new OrionThreadFactory());
        try {
            JGitGlobalRuntime globalRuntime = executor.submit(JGitGlobalRuntime::new).get(1, TimeUnit.SECONDS);
            globalRuntime.initializeGlobalExecutors();

            Thread workQueueThread = WorkQueue.getExecutor()
                    .submit(Thread::currentThread)
                    .get(1, TimeUnit.SECONDS);

            assertThat(workQueueThread.isDaemon()).isTrue();
            assertThat(hasThreadGroupNamed(workQueueThread, "OrionExecutor")).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("can repeat JGit global executor lifecycle")
    void canRepeatJGitGlobalExecutorLifecycle() {
        installDefaultControlledJGitRuntime();
        JGitGlobalRuntime globalRuntime = new JGitGlobalRuntime();

        assertThatCode(() -> {
            globalRuntime.initializeGlobalExecutors();
            globalRuntime.initializeGlobalExecutors();
            pushToBareRepository(tempDir.resolve("first-cycle"));

            globalRuntime.shutdownGlobalExecutors();
            globalRuntime.shutdownGlobalExecutors();

            globalRuntime.initializeGlobalExecutors();
            globalRuntime.initializeGlobalExecutors();
            pushToBareRepository(tempDir.resolve("second-cycle"));
        }).doesNotThrowAnyException();
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

    private static void pushToBareRepository(Path root) throws Exception {
        Path bareRepository = root.resolve("bare.git");
        Files.createDirectories(bareRepository);
        try (Git ignored = Git.init()
                .setBare(true)
                .setGitDir(bareRepository.toFile())
                .setInitialBranch(BRANCH)
                .call()) {
            // The bare repository is the target for a normal local push.
        }

        Path workTree = root.resolve("worktree");
        try (Git git = Git.init()
                .setDirectory(workTree.toFile())
                .setInitialBranch(BRANCH)
                .call()) {
            git.getRepository().getConfig().setString("user", null, "name", "JGit Runtime Test");
            git.getRepository().getConfig().setString("user", null, "email", "jgit-runtime@example.test");
            git.getRepository().getConfig().save();

            Files.writeString(workTree.resolve("README.md"), "jgit runtime restart\n");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setAuthor("JGit Runtime Test", "jgit-runtime@example.test")
                    .setCommitter("JGit Runtime Test", "jgit-runtime@example.test")
                    .setMessage("verify runtime restart")
                    .call();
            git.push()
                    .setRemote(bareRepository.toUri().toString())
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                    .call();
        }
    }

    private static boolean hasThreadGroupNamed(Thread thread, String name) {
        ThreadGroup group = thread.getThreadGroup();
        while (group != null) {
            if (group.getName().equals(name)) {
                return true;
            }
            group = group.getParent();
        }
        return false;
    }
}
