package pro.deta.orion.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;
import ch.qos.logback.classic.Logger;
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
