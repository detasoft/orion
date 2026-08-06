package pro.deta.orion.test.duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAnalyticsReportTest {
    @Test
    void writesSummaryCsvFilesAndEmptyFlameGraphs(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("test-durations.jsonl"), testDurationLine("A#fast", "SUCCESSFUL", 12, "")
                + testDurationLine("A#slow", "FAILED", 34, "boom"), StandardCharsets.UTF_8);

        TestAnalyticsReport.ReportFiles files = TestAnalyticsReport.generate(temp, 10);

        String summary = Files.readString(files.summary());
        String html = Files.readString(files.html());
        assertTrue(summary.contains("Tests: 2"));
        assertTrue(summary.contains("Failed: 1"));
        assertTrue(summary.contains("Byte Array Test Allocation Hotspots"));
        assertTrue(html.contains("table class=\"sortable\""));
        assertTrue(html.contains("A#fast"));
        assertTrue(html.contains("compareCells"));
        assertTrue(html.contains("main { box-sizing: border-box; width: 100%; padding: 24px; }"));
        assertTrue(html.contains("table { border-collapse: collapse; width: 100%; min-width: 100%; }"));
        assertTrue(html.contains("id=\"hide-classloader-allocations\" type=\"checkbox\" checked"));
        assertTrue(html.contains("applyClassloaderAllocationFilter"));
        assertTrue(Files.isRegularFile(files.testsCsv()));
        assertTrue(Files.isRegularFile(files.modulesCsv()));
        assertTrue(Files.isRegularFile(files.testAllocationsCsv()));
        assertTrue(Files.isRegularFile(files.byteArrayTestAllocationsCsv()));
        assertTrue(Files.isRegularFile(files.allocationsCsv()));
        assertTrue(Files.isRegularFile(files.byteArrayAllocationsCsv()));
        assertTrue(Files.isRegularFile(files.testAllocationFlameGraph()));
        assertTrue(Files.readString(files.cpuFlameGraph()).contains("No stack samples"));
    }

    @Test
    void writesFlameGraphFromCollapsedStacks(@TempDir Path temp) throws Exception {
        Path output = temp.resolve("flamegraph.svg");

        TestAnalyticsReport.writeFlameGraph(output, "Allocations", "bytes", Map.of(
                "root;parser;byteArray", 128L,
                "root;writer;byteArray", 64L
        ));

        String svg = Files.readString(output);
        assertTrue(svg.contains("Allocations"));
        assertTrue(svg.contains("parser"));
        assertTrue(svg.contains("writer"));
    }

    @Test
    void rendersStackFramesOnSeparateHtmlLines() {
        StringBuilder html = new StringBuilder();

        TestAnalyticsReport.appendHtmlStackCell(html, "root;parser.Frame.method;writer.Frame.write");

        String output = html.toString();
        assertTrue(output.contains("class=\"stack-cell\""));
        assertTrue(output.contains("<span class=\"stack-frame\">root</span>"));
        assertTrue(output.contains("<span class=\"stack-frame\">parser.Frame.method</span>"));
        assertTrue(output.contains("<span class=\"stack-frame\">writer.Frame.write</span>"));
    }

    @Test
    void formatsReportUriAsFileLink(@TempDir Path temp) {
        String uri = TestAnalyticsReport.reportUri(temp.resolve("index.html"));

        assertTrue(uri.startsWith("file:"));
        assertTrue(uri.endsWith("/index.html"));
    }

    @Test
    void recognizesClassloaderAllocationStacks() {
        assertTrue(TestAnalyticsReport.isClassLoadingAllocationStack(
                "test;java.lang.ClassLoader.loadClass;jdk.internal.loader.URLClassPath$JarLoader$1.getBytes"));
        assertFalse(TestAnalyticsReport.isTestAllocationStack(
                "test;java.lang.ClassLoader.loadClass;jdk.internal.loader.URLClassPath$JarLoader$1.getBytes"));
        assertFalse(TestAnalyticsReport.isTestAllocationStack(
                "test;java.util.ServiceLoader$LazyClassPathLookupIterator.hasNextService;"
                        + "java.lang.ClassLoader.getResources"));
        assertTrue(TestAnalyticsReport.isTestAllocationStack(
                "test;pro.deta.orion.git.parser.wire.GitNativeClientOutput.write"));
    }

    private static String testDurationLine(String testId, String status, long durationMillis, String reason) {
        return "{"
                + "\"runId\":\"run-1\","
                + "\"module\":\"core/git-parser\","
                + "\"testId\":\"" + testId + "\","
                + "\"className\":\"A\","
                + "\"methodName\":\"" + testId.substring(testId.indexOf('#') + 1) + "\","
                + "\"displayName\":\"" + testId + "\","
                + "\"status\":\"" + status + "\","
                + "\"durationMillis\":" + durationMillis + ","
                + "\"startedAt\":\"2026-08-06T00:00:00Z\","
                + "\"finishedAt\":\"2026-08-06T00:00:00Z\","
                + "\"reason\":\"" + reason + "\""
                + "}\n";
    }
}
