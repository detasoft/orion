package pro.deta.orion.util;

import org.slf4j.helpers.MessageFormatter;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;

public class LogUtils {
    public static void switchAppLoggerDefault() {
        OrionApplicationLifecycle.BOOTSTRAP.getLogInitializer().setLevel("pro.deta.orion", "WARN");
    }
    public static void switchAppLoggerTrace() {
        OrionApplicationLifecycle.BOOTSTRAP.getLogInitializer().setLevel("pro.deta.orion", "TRACE");
    }
    public static void switchStreamLoggerOn() {
        OrionApplicationLifecycle.BOOTSTRAP.getLogInitializer().setLevel("pro.deta.orion.util.stream", "TRACE");
    }

    public static String formatMessage(String template, Object ...args) {
        return MessageFormatter.arrayFormat(template, args).getMessage();
    }

    public static String getStackTraceAsString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Thread [")
                .append(Thread.currentThread().getName())
                .append("] stack trace:\n");

        for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
            sb.append("\tat ").append(ste).append("\n");
        }
        return sb.toString();
    }
}
