package pro.deta.orion.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.internal.storage.file.GC;
import org.eclipse.jgit.lib.internal.WorkQueue;
import org.eclipse.jgit.util.FS;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final ThreadGroup JGIT_THREAD_GROUP = new ThreadGroup(rootThreadGroup(), "JGit");
    private static final AtomicBoolean WORK_QUEUE_INITIALIZED = new AtomicBoolean(false);

    @Inject
    public JGitGlobalRuntime() {
        initializeWorkQueueInJGitThreadGroup();
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
                executor.setThreadFactory(daemonThreadFactory("JGit-FileStoreAttributeReader"));
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

    static ThreadFactory daemonThreadFactory(String name) {
        AtomicInteger threadNumber = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(JGIT_THREAD_GROUP, runnable, name + "-" + threadNumber.getAndIncrement(), 0, false);
            thread.setContextClassLoader(null);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void initializeWorkQueueInJGitThreadGroup() {
        if (!WORK_QUEUE_INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        Thread initializer = new Thread(JGIT_THREAD_GROUP, WorkQueue::getExecutor, "JGit-WorkQueue-initializer", 0, false);
        initializer.setContextClassLoader(null);
        initializer.setDaemon(true);
        initializer.start();
        try {
            initializer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while initializing JGit WorkQueue", e);
        }
    }

    private static ThreadGroup rootThreadGroup() {
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        while (group.getParent() != null) {
            group = group.getParent();
        }
        return group;
    }
}
