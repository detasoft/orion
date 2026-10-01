package pro.deta.orion.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopedLogsTest {
    @TempDir Path root;

    @AfterEach
    void resetLogging() {
        MDC.clear();
        new LogInitializer();
    }

    @Test
    void duplicatesWholeEventsIntoTaskAndUserFilesAndSurvivesReconfiguration() throws Exception {
        LogInitializer logging = new LogInitializer();
        logging.configureScopedLogs(root);
        try (LogScope user = LogScope.user("alice@example.test")) {
            try (LogScope task = LogScope.task("acme-certificate")) {
                LoggerFactory.getLogger("first.class").error("first\nsecond",
                        new IllegalStateException("certificate failure"));
            }
            LoggerFactory.getLogger("second.class").info("user only");
        }
        LoggerFactory.getLogger("first.class").info("unscoped");
        ScopedLogs logs = logging.getScopedLogs();
        String taskText = logs.read("tasks", "acme-certificate", "current.log", 0, null).text();
        String userText = logs.read("users", "alice@example.test", "current.log", 0, null).text();
        assertThat(taskText).contains("first\nsecond", "IllegalStateException: certificate failure",
                "ScopedLogsTest.java:", "taskId=acme-certificate", "userId=alice@example.test")
                .doesNotContain("user only", "unscoped");
        assertThat(userText).startsWith(taskText).contains("user only").doesNotContain("unscoped");
        assertThat(logs.list("tasks")).hasSize(1);
        assertThat(logs.list("users")).hasSize(1);
        logging.configure();
        try (LogScope ignored = LogScope.task("acme-certificate")) {
            LoggerFactory.getLogger("another.class").info("automatic renewal");
        }
        assertThat(logs.read("tasks", "acme-certificate", "current.log", 0, null).text())
                .startsWith(taskText).contains("automatic renewal");
        assertThat(logs.read("users", "alice@example.test", "current.log", 0, null).text()).isEqualTo(userText);
        LogInitializer restarted = new LogInitializer();
        restarted.configureScopedLogs(root);
        assertThat(restarted.getScopedLogs().list("tasks")).hasSize(1);
    }

    @Test
    void restoresNestedContextAndDoesNotLeakItToTheNextOperation() {
        MDC.put("unrelated", "retained");
        try (LogScope user = LogScope.user("alice")) {
            try (LogScope task = LogScope.task("certificate")) {
                assertThat(MDC.get("userId")).isEqualTo("alice");
                assertThat(MDC.get("taskId")).isEqualTo("certificate");
            }
            assertThat(MDC.get("taskId")).isNull();
        }
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("unrelated")).isEqualTo("retained");
    }

    @RepeatedTest(50)
    void isolatesConcurrentUsersAndTasksOnReusedThreads() throws Exception {
        LogInitializer logging = new LogInitializer();
        logging.configureScopedLogs(root);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> writes = new ArrayList<>();
            for (int number = 0; number < 10; number++) {
                String id = Integer.toString(number);
                writes.add(executor.submit(() -> {
                    try (LogScope user = LogScope.user("user-" + id); LogScope task = LogScope.task("task-" + id)) {
                        LoggerFactory.getLogger("concurrent.scope").info("owned by {}", id);
                    }
                    assertThat(MDC.get("userId")).isNull();
                    assertThat(MDC.get("taskId")).isNull();
                }));
            }
            for (Future<?> write : writes) write.get();
        }
        for (int number = 0; number < 10; number++) {
            String task = logging.getScopedLogs().read("tasks", "task-" + number, "current.log", 0, null).text();
            String user = logging.getScopedLogs().read("users", "user-" + number, "current.log", 0, null).text();
            assertThat(task).isEqualTo(user).contains("owned by " + number);
            assertThat(task.lines().count()).isEqualTo(1);
        }
    }

    @Test
    void rotatesAnExistingLargeFileAndKeepsTheArchiveReadable() throws Exception {
        LogInitializer logging = new LogInitializer();
        logging.configureScopedLogs(root);
        try (LogScope ignored = LogScope.task("rotation")) {
            LoggerFactory.getLogger("rotation.test").info("before rotation");
        }
        ScopedLogs.Page before = logging.getScopedLogs().read("tasks", "rotation", "current.log", 0, null);
        Path current;
        try (Stream<Path> paths = Files.walk(root)) {
            current = paths.filter(path -> path.getFileName().toString().equals("current.log"))
                    .findFirst().orElseThrow();
        }
        Files.writeString(current, "x".repeat(10 * 1024 * 1024));
        logging.configure();
        try (LogScope ignored = LogScope.task("rotation")) {
            LoggerFactory.getLogger("rotation.test").info("after rotation");
        }
        assertThat(logging.getScopedLogs().list("tasks")).extracting(ScopedLogs.LogFile::file)
                .containsExactlyInAnyOrder("archive.1.log", "current.log");
        assertThat(logging.getScopedLogs().read("tasks", "rotation", "archive.1.log", 0, null).text())
                .hasSize(64 * 1024);
        assertThat(logging.getScopedLogs().read("tasks", "rotation", "current.log", 0, null).text())
                .contains("after rotation");
        assertThatThrownBy(() -> logging.getScopedLogs().read("tasks", "rotation", "current.log",
                before.nextOffset(), before.version())).isInstanceOf(ScopedLogs.ChangedFileException.class);
    }

    @Test
    void pagesUtf8WithoutLosingBytesAndRejectsFileTraversalAndReplacedFiles() throws Exception {
        LogInitializer logging = new LogInitializer();
        logging.configureScopedLogs(root);
        String message = "Ж🙂".repeat(15_000);
        try (LogScope ignored = LogScope.task("../certificate")) {
            LoggerFactory.getLogger("first.class").info(message);
        }
        ScopedLogs logs = logging.getScopedLogs();
        ScopedLogs.Page first = logs.read("tasks", "../certificate", "current.log", 0, null);
        assertThat(first.more()).isTrue();
        ScopedLogs.Page next = logs.read("tasks", "../certificate", "current.log",
                first.nextOffset(), first.version());
        assertThat(first.text() + next.text()).contains(message);
        assertThatThrownBy(() -> logs.read("tasks", "../certificate", "../../outside", 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> logs.list("../users")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> logs.read("tasks", "../certificate", "current.log", 1, "stale"))
                .isInstanceOf(ScopedLogs.ChangedFileException.class);
        assertThat(Files.exists(root.resolveSibling("certificate"))).isFalse();
    }
}
