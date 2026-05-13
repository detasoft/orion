package pro.deta.orion.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.internal.storage.file.GC;
import org.eclipse.jgit.lib.internal.WorkQueue;
import org.eclipse.jgit.util.FS;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates JGit process-wide executors without terminating their static-final pools.
 *
 * <p>JGit can reuse these executors after an application lifecycle restart in the same JVM, but it cannot recreate
 * them after {@code shutdownNow()}. Orion therefore lets their idle threads expire instead of stopping the executor
 * objects themselves.</p>
 */
@Slf4j
@Singleton
public class JGitGlobalRuntime {
    private static final int IDLE_WORK_QUEUE_KEEP_ALIVE_SECONDS = 5;
    private static final int STOPPING_WORK_QUEUE_KEEP_ALIVE_SECONDS = 1;
    private static final int FILE_STORE_KEEP_ALIVE_SECONDS = 1;

    @Inject
    public JGitGlobalRuntime() {
    }

    public synchronized void initializeGlobalExecutors() {
        configureWorkQueueExecutor(IDLE_WORK_QUEUE_KEEP_ALIVE_SECONDS, false);
        configureFileStoreAttributeExecutor("FUTURE_RUNNER");
        configureFileStoreAttributeExecutor("SAVE_RUNNER");
        GC.setExecutor(WorkQueue.getExecutor());
        FS.FileStoreAttributes.setBackground(false);
        FS.FileStoreAttributes.get(Path.of("").toAbsolutePath());
    }

    public synchronized void shutdownGlobalExecutors() {
        GC.setExecutor(null);
        releaseWorkQueueThreads();
        releaseFileStoreAttributeThreads("FUTURE_RUNNER");
        releaseFileStoreAttributeThreads("SAVE_RUNNER");
    }

    private void configureWorkQueueExecutor(int keepAliveSeconds, boolean allowCoreThreadTimeout) {
        ScheduledThreadPoolExecutor executor = WorkQueue.getExecutor();
        executor.setThreadFactory(daemonThreadFactory("JGit-WorkQueue"));
        executor.setKeepAliveTime(keepAliveSeconds, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(allowCoreThreadTimeout);
    }

    private void configureFileStoreAttributeExecutor(String fieldName) {
        try {
            Field field = FS.FileStoreAttributes.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof ThreadPoolExecutor executor) {
                executor.setKeepAliveTime(FILE_STORE_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
                executor.allowCoreThreadTimeOut(true);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warn("Unable to configure JGit FileStoreAttributes executor {}", fieldName, e);
        }
    }

    private void releaseWorkQueueThreads() {
        ScheduledThreadPoolExecutor executor = WorkQueue.getExecutor();
        configureWorkQueueExecutor(STOPPING_WORK_QUEUE_KEEP_ALIVE_SECONDS, true);
        executor.purge();
    }

    private void releaseFileStoreAttributeThreads(String fieldName) {
        configureFileStoreAttributeExecutor(fieldName);
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        ThreadFactory baseFactory = Executors.defaultThreadFactory();
        return runnable -> {
            Thread thread = baseFactory.newThread(runnable);
            thread.setName(name);
            thread.setContextClassLoader(null);
            thread.setDaemon(true);
            return thread;
        };
    }
}
