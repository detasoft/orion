package pro.deta.orion.util;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.internal.BooleanCondition;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static pro.deta.orion.internal.async.AsyncThrowable.findIndexOfFirstNonSkipClass;

@Slf4j
public class OrionUtils {
    public static final JVM_RUN_MODE JVM_MODE = JVM_RUN_MODE.value();
    private final static OrionPasswordHashingService ophs = new OrionPasswordHashingService();

    public enum JVM_RUN_MODE {
        DEFAULT, JVM_DEBUG;

        private static JVM_RUN_MODE value() {
            try {
                return java.lang.management.ManagementFactory.getRuntimeMXBean().
                        getInputArguments().stream().anyMatch(it -> it.contains("jdwp")) ? JVM_DEBUG : DEFAULT;
            } catch (Exception e) {
                log.error("Unrecoverable error", e);
                return DEFAULT;
            }
        }

        public boolean isDebug() {
            return this == JVM_DEBUG;
        }
    }

    public static boolean isNullOrEmpty(String string) {
        return string == null || string.isEmpty();
    }

    public static <V> Result<V> waitForCompletion(Future<V> f, int seconds) {
        try {
            if (seconds == 0 || OrionUtils.underDebugSession()) {
                return new Result.Success<>(f.get());
            } else if (seconds > 0 && OrionUtils.underDebugSession()) {
                log.info("Should wait in debug for {} seconds", seconds);
                return new Result.Success<>(f.get());
            } else {
                return new Result.Success<>(f.get(seconds, TimeUnit.SECONDS));
            }
        } catch (TimeoutException e) {
            return new Result.Failure<>(Result.FailureCode.TIMEOUT);
        } catch (Exception e) {
            log.error("Error of waiting for a future {}", f, e);
            return new Result.Failure<>(Result.FailureCode.GENERAL);
        }
    }

    public static <V> Result<V> waitForCompletion(Future<V> f) {
        return waitForCompletion(f, 5);
    }

    public static <V> V wrapInThreadName(String threadName, Callable<V> call) {
        String oldName = setThreadName(threadName);
        try {
            return call.call();
        } catch (SecurityException e) {
            log.error("Calling {} is error", call, e);
        } catch (Exception e) {
            log.error("Calling {} is error", call, e);
            throw new RuntimeException(e);
        } finally {
            setThreadName(oldName);
        }
        return null;
    }


    public static void wrapRunnableInThreadName(String threadName, Runnable call) {
        String oldName = setThreadName(threadName);
        try {
            call.run();
        } catch (SecurityException e) {
            log.error("Calling {} is error", call, e);
        } catch (Exception e) {
            log.error("Calling {} is error", call, e);
            throw new RuntimeException(e);
        } finally {
            setThreadName(oldName);
        }
    }

    public static void wrapRunnableWithThreadName(String threadName, Runnable call) {
        wrapInThreadName(threadName, () -> {
           call.run();
           return 0;
        });
    }

    private static String setThreadName(String threadName) {
        String name = Thread.currentThread().getName();
        Thread.currentThread().setName(threadName);
        return name;
    }

    public static void waitAll(List<Future<? extends Object>> fs) {
        for(Future<?> f: fs) {
            waitForCompletion(f);
        }
    }

    public static char[] generatePassword(int length) {
        return ophs.generateSecureRandomPassword(length);
    }

    public static String initiatorOf(Class<?> cls) {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

        int firstNonSkip = findIndexOfFirstNonSkipClass(stackTrace);
        if (firstNonSkip >= 0) {
            StackTraceElement el = stackTrace[firstNonSkip];
            if (cls.isSynthetic())
                return formatRegisteredAt(el);
            else
                return cls.getName() + formatRegisteredAt(el);
        }
        return "unknown";
    }

    private static String formatRegisteredAt(StackTraceElement el) {
        return "Registered at "+ el.getClassName() + "(" + el.getFileName() + ":" + el.getLineNumber() + ")";
    }

    public static String getCurrentPid() {
        return java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getName().split("@")[0];
    }

    public static boolean underDebugSession() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments().toString().contains("jdwp");
    }

    public static boolean waitForCondition(BooleanCondition condition) {
        return waitForCondition(condition, 5, TimeUnit.SECONDS);
    }

    public static boolean waitForCondition(BooleanCondition condition, long timeout, TimeUnit unit) {
        final long timeoutNanos = unit.toNanos(timeout);
        final long start = System.nanoTime();
        while (!condition.check()) {
            if (System.nanoTime() - start >= timeoutNanos) {
                return false; // timed out
            }
            LockSupport.parkNanos(100_000);
        }
        return true; // condition met
    }
}
