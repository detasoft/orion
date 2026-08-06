package pro.deta.orion.test.duration;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class TestAnalyticsReport {
    private static final int DEFAULT_TOP_LIMIT = 50;
    private static final int SVG_WIDTH = 1600;
    private static final int FRAME_HEIGHT = 18;
    private static final int FONT_SIZE = 12;
    private static final Set<String> CPU_SAMPLE_EVENTS = Set.of(
            "jdk.ExecutionSample",
            "jdk.NativeMethodSample"
    );
    private static final Set<String> ALLOCATION_SAMPLE_EVENTS = Set.of(
            "jdk.ObjectAllocationSample",
            "jdk.ObjectAllocationInNewTLAB",
            "jdk.ObjectAllocationOutsideTLAB"
    );

    private TestAnalyticsReport() {
    }

    public static void main(String[] args) throws IOException {
        Path runDirectory = args.length == 0
                ? latestRunDirectory(Paths.get("target", "test-analytics"))
                : Paths.get(args[0]);
        int topLimit = args.length >= 2 ? Integer.parseInt(args[1]) : DEFAULT_TOP_LIMIT;
        ReportFiles report = generate(runDirectory, topLimit);
        System.out.printf("[orion-test-analytics] HTML report: %s%n",
                report.html().toAbsolutePath().normalize());
        System.out.printf("[orion-test-analytics] report: %s%n", report.summary().toAbsolutePath().normalize());
        System.out.printf("[orion-test-analytics] CPU flame graph: %s%n",
                report.cpuFlameGraph().toAbsolutePath().normalize());
        System.out.printf("[orion-test-analytics] allocation flame graph: %s%n",
                report.allocationFlameGraph().toAbsolutePath().normalize());
    }

    static ReportFiles generate(Path runDirectory, int topLimit) throws IOException {
        Path normalizedRunDirectory = runDirectory.toAbsolutePath().normalize();
        Files.createDirectories(normalizedRunDirectory);

        List<TestRecord> tests = readTestRecords(normalizedRunDirectory.resolve("test-durations.jsonl"));
        JfrReport jfr = readJfrRecordings(normalizedRunDirectory);
        List<ModuleSummary> modules = summarizeModules(tests);

        Path testsCsv = normalizedRunDirectory.resolve("tests.csv");
        Path modulesCsv = normalizedRunDirectory.resolve("modules.csv");
        Path allocationsCsv = normalizedRunDirectory.resolve("allocations.csv");
        Path byteArrayAllocationsCsv = normalizedRunDirectory.resolve("byte-array-allocations.csv");
        Path jfrEventsCsv = normalizedRunDirectory.resolve("jfr-events.csv");
        Path html = normalizedRunDirectory.resolve("index.html");
        Path summary = normalizedRunDirectory.resolve("summary.md");
        Path cpuFlameGraph = normalizedRunDirectory.resolve("flamegraph-cpu.svg");
        Path allocationFlameGraph = normalizedRunDirectory.resolve("flamegraph-alloc.svg");

        writeTestsCsv(testsCsv, tests);
        writeModulesCsv(modulesCsv, modules);
        writeAllocationsCsv(allocationsCsv, jfr.allocationHotspots());
        writeAllocationsCsv(byteArrayAllocationsCsv, byteArrayAllocations(jfr.allocationHotspots()));
        writeJfrEventsCsv(jfrEventsCsv, jfr.eventCounts());
        writeFlameGraph(cpuFlameGraph, "CPU samples", "samples", jfr.cpuStacks());
        writeFlameGraph(allocationFlameGraph, "Allocation samples", "bytes", jfr.allocationStacks());
        writeSummary(summary, normalizedRunDirectory, tests, modules, jfr, topLimit);
        writeHtmlReport(html, normalizedRunDirectory, tests, modules, jfr);

        return new ReportFiles(html, summary, testsCsv, modulesCsv, allocationsCsv, byteArrayAllocationsCsv,
                jfrEventsCsv, cpuFlameGraph, allocationFlameGraph);
    }

    private static Path latestRunDirectory(Path analyticsRoot) throws IOException {
        if (!Files.isDirectory(analyticsRoot)) {
            throw new IOException("Analytics directory does not exist: "
                    + analyticsRoot.toAbsolutePath().normalize());
        }
        try (Stream<Path> entries = Files.list(analyticsRoot)) {
            Optional<Path> latest = entries
                    .filter(Files::isDirectory)
                    .max(Comparator.comparingLong(TestAnalyticsReport::lastModifiedMillis));
            return latest.orElseThrow(() -> new IOException("No analytics run directories in "
                    + analyticsRoot.toAbsolutePath().normalize()));
        }
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    private static List<TestRecord> readTestRecords(Path durationsFile) throws IOException {
        if (!Files.isRegularFile(durationsFile)) {
            return List.of();
        }

        List<TestRecord> records = new ArrayList<>();
        for (String line : Files.readAllLines(durationsFile, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                records.add(parseTestRecord(line));
            }
        }
        return records;
    }

    private static TestRecord parseTestRecord(String json) {
        return new TestRecord(
                jsonString(json, "runId"),
                jsonString(json, "module"),
                jsonString(json, "testId"),
                jsonString(json, "className"),
                jsonString(json, "methodName"),
                jsonString(json, "displayName"),
                jsonString(json, "status"),
                jsonLong(json, "durationMillis"),
                jsonString(json, "startedAt"),
                jsonString(json, "finishedAt"),
                jsonString(json, "reason")
        );
    }

    private static String jsonString(String json, String key) {
        int offset = jsonValueOffset(json, key);
        if (offset < 0 || offset >= json.length() || json.charAt(offset) != '"') {
            return "";
        }

        StringBuilder value = new StringBuilder();
        for (int i = offset + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                return value.toString();
            }
            if (c != '\\' || i + 1 >= json.length()) {
                value.append(c);
                continue;
            }

            char escaped = json.charAt(++i);
            switch (escaped) {
                case '"' -> value.append('"');
                case '\\' -> value.append('\\');
                case '/' -> value.append('/');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (i + 4 < json.length()) {
                        value.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> value.append(escaped);
            }
        }
        return value.toString();
    }

    private static long jsonLong(String json, String key) {
        int offset = jsonValueOffset(json, key);
        if (offset < 0) {
            return 0;
        }

        int end = offset;
        while (end < json.length() && "-0123456789".indexOf(json.charAt(end)) >= 0) {
            end++;
        }
        if (end == offset) {
            return 0;
        }
        return Long.parseLong(json.substring(offset, end));
    }

    private static int jsonValueOffset(String json, String key) {
        String marker = "\"" + key + "\":";
        int offset = json.indexOf(marker);
        if (offset < 0) {
            return -1;
        }
        offset += marker.length();
        while (offset < json.length() && Character.isWhitespace(json.charAt(offset))) {
            offset++;
        }
        return offset;
    }

    private static JfrReport readJfrRecordings(Path runDirectory) throws IOException {
        List<Path> files = findJfrFiles(runDirectory);
        Map<String, Long> eventCounts = new TreeMap<>();
        Map<String, Long> cpuStacks = new HashMap<>();
        Map<String, Long> allocationStacks = new HashMap<>();
        Map<AllocationKey, AllocationStats> allocationHotspots = new HashMap<>();
        Map<Path, String> unreadableFiles = new LinkedHashMap<>();

        for (Path file : files) {
            try (RecordingFile recordingFile = new RecordingFile(file)) {
                while (recordingFile.hasMoreEvents()) {
                    RecordedEvent event = recordingFile.readEvent();
                    String eventName = event.getEventType().getName();
                    eventCounts.merge(eventName, 1L, Long::sum);
                    if (CPU_SAMPLE_EVENTS.contains(eventName)) {
                        addStack(cpuStacks, event, 1);
                    } else if (ALLOCATION_SAMPLE_EVENTS.contains(eventName)) {
                        long bytes = allocationWeight(event);
                        addStack(allocationStacks, event, bytes);
                        addAllocationHotspot(allocationHotspots, event, eventName, bytes);
                    }
                }
            } catch (IOException | RuntimeException e) {
                unreadableFiles.put(file, e.toString());
            }
        }

        return new JfrReport(files, unreadableFiles, eventCounts, cpuStacks, allocationStacks,
                allocationHotspots.values().stream()
                        .sorted(AllocationStats.ORDER)
                        .toList());
    }

    private static List<Path> findJfrFiles(Path runDirectory) throws IOException {
        if (!Files.isDirectory(runDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(runDirectory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".jfr"))
                    .sorted()
                    .toList();
        }
    }

    private static long allocationWeight(RecordedEvent event) {
        if (event.hasField("allocationSize")) {
            long bytes = event.getLong("allocationSize");
            return Math.max(1, bytes);
        }
        if (event.hasField("weight")) {
            long bytes = event.getLong("weight");
            return Math.max(1, bytes);
        }
        return 1;
    }

    private static void addStack(Map<String, Long> stacks, RecordedEvent event, long weight) {
        stackTrace(event).ifPresent(stack -> stacks.merge(stack, Math.max(1, weight), Long::sum));
    }

    private static void addAllocationHotspot(Map<AllocationKey, AllocationStats> hotspots, RecordedEvent event,
                                             String eventName, long bytes) {
        String stack = stackTrace(event).orElse("[no-stack]");
        String objectClass = objectClass(event);
        AllocationKey key = new AllocationKey(eventName, objectClass, stack);
        hotspots.computeIfAbsent(key, AllocationStats::new).add(bytes);
    }

    private static Optional<String> stackTrace(RecordedEvent event) {
        RecordedStackTrace stackTrace = event.getStackTrace();
        if (stackTrace == null || stackTrace.getFrames().isEmpty()) {
            return Optional.empty();
        }

        List<RecordedFrame> frames = stackTrace.getFrames();
        List<String> stack = new ArrayList<>(frames.size());
        for (int i = frames.size() - 1; i >= 0; i--) {
            stack.add(frameName(frames.get(i)));
        }
        return Optional.of(String.join(";", stack));
    }

    private static String frameName(RecordedFrame frame) {
        RecordedMethod method = frame.getMethod();
        if (method == null) {
            return "[unknown]";
        }

        String className = method.getType() == null ? "[unknown]" : method.getType().getName();
        return className + "." + method.getName();
    }

    private static String objectClass(RecordedEvent event) {
        if (!event.hasField("objectClass")) {
            return "[unknown]";
        }

        return normalizeClassName(event.getClass("objectClass").getName());
    }

    private static String normalizeClassName(String className) {
        if (className == null) {
            return "[unknown]";
        }
        return switch (className) {
            case "[B" -> "byte[]";
            case "[C" -> "char[]";
            case "[I" -> "int[]";
            case "[J" -> "long[]";
            case "[S" -> "short[]";
            case "[Z" -> "boolean[]";
            case "[F" -> "float[]";
            case "[D" -> "double[]";
            default -> normalizeObjectArrayName(className);
        };
    }

    private static String normalizeObjectArrayName(String className) {
        if (className == null || !className.startsWith("[L") || !className.endsWith(";")) {
            return Objects.toString(className, "[unknown]");
        }
        return className.substring(2, className.length() - 1) + "[]";
    }

    private static List<ModuleSummary> summarizeModules(List<TestRecord> records) {
        Map<String, ModuleSummaryBuilder> builders = new TreeMap<>();
        for (TestRecord record : records) {
            builders.computeIfAbsent(record.module(), ModuleSummaryBuilder::new).add(record);
        }
        return builders.values().stream()
                .map(ModuleSummaryBuilder::build)
                .sorted(Comparator.comparing(ModuleSummary::module))
                .toList();
    }

    private static void writeTestsCsv(Path output, List<TestRecord> records) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("runId,module,status,durationMillis,className,methodName,testId,"
                + "displayName,startedAt,finishedAt,reason");
        records.stream()
                .sorted(Comparator.comparingLong(TestRecord::durationMillis).reversed())
                .forEach(record -> lines.add(csv(
                        record.runId(),
                        record.module(),
                        record.status(),
                        Long.toString(record.durationMillis()),
                        record.className(),
                        record.methodName(),
                        record.testId(),
                        record.displayName(),
                        record.startedAt(),
                        record.finishedAt(),
                        record.reason()
                )));
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    private static void writeModulesCsv(Path output, List<ModuleSummary> modules) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("module,total,successful,failed,aborted,skipped,totalMillis,averageMillis,maxMillis");
        for (ModuleSummary module : modules) {
            lines.add(csv(
                    module.module(),
                    Integer.toString(module.total()),
                    Integer.toString(module.successful()),
                    Integer.toString(module.failed()),
                    Integer.toString(module.aborted()),
                    Integer.toString(module.skipped()),
                    Long.toString(module.totalMillis()),
                    Long.toString(module.averageMillis()),
                    Long.toString(module.maxMillis())
            ));
        }
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    private static void writeAllocationsCsv(Path output, List<AllocationStats> allocations) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("event,objectClass,samples,bytes,topFrame,stack");
        for (AllocationStats allocation : allocations) {
            lines.add(csv(
                    allocation.eventName(),
                    allocation.objectClass(),
                    Long.toString(allocation.samples()),
                    Long.toString(allocation.bytes()),
                    allocation.topFrame(),
                    allocation.stack()
            ));
        }
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    private static List<AllocationStats> byteArrayAllocations(List<AllocationStats> allocations) {
        return allocations.stream()
                .filter(allocation -> "byte[]".equals(allocation.objectClass()))
                .toList();
    }

    private static void writeJfrEventsCsv(Path output, Map<String, Long> eventCounts) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("event,count");
        Comparator<Map.Entry<String, Long>> byCountThenName =
                Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey());
        eventCounts.entrySet().stream()
                .sorted(byCountThenName)
                .forEach(entry -> lines.add(csv(entry.getKey(), Long.toString(entry.getValue()))));
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    static void writeFlameGraph(Path output, String title, String units, Map<String, Long> collapsedStacks)
            throws IOException {
        FlameNode root = new FlameNode("(root)");
        for (Map.Entry<String, Long> entry : collapsedStacks.entrySet()) {
            if (entry.getValue() <= 0 || entry.getKey().isBlank()) {
                continue;
            }
            root.add(entry.getKey().split(";"), entry.getValue());
        }

        if (root.value() == 0) {
            writeEmptyFlameGraph(output, title);
            return;
        }

        int depth = root.depth();
        int chartHeight = Math.max(FRAME_HEIGHT, depth * FRAME_HEIGHT);
        int height = chartHeight + 80;
        StringBuilder svg = new StringBuilder(64_000);
        svg.append("""
                <svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">
                <style>
                text { font-family: Menlo, Consolas, monospace; font-size: %dpx; fill: #111; }
                .title { font-size: 18px; font-weight: 700; }
                .meta { fill: #555; }
                .frame { stroke: #fff; stroke-width: .5; }
                </style>
                """.formatted(SVG_WIDTH, height, SVG_WIDTH, height, FONT_SIZE));
        svg.append("<text x=\"12\" y=\"24\" class=\"title\">").append(xml(title)).append("</text>\n");
        svg.append("<text x=\"12\" y=\"44\" class=\"meta\">total: ")
                .append(formatNumber(root.value())).append(' ').append(xml(units)).append("</text>\n");

        double chartWidth = SVG_WIDTH - 24.0;
        renderFlameChildren(svg, root, 1, 12.0, 56.0 + chartHeight, chartWidth, units);
        svg.append("</svg>\n");
        Files.writeString(output, svg.toString(), StandardCharsets.UTF_8);
    }

    private static void writeEmptyFlameGraph(Path output, String title) throws IOException {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="1200" height="160" viewBox="0 0 1200 160">
                <style>
                text { font-family: Menlo, Consolas, monospace; fill: #111; }
                .title { font-size: 18px; font-weight: 700; }
                .meta { font-size: 13px; fill: #555; }
                </style>
                <text x="12" y="28" class="title">%s</text>
                <text x="12" y="58" class="meta">No stack samples were found in the JFR recordings.</text>
                <text x="12" y="82" class="meta">Use profile JFR settings and forked tests.</text>
                <text x="12" y="106" class="meta">Rerun the report generator to collect sample events.</text>
                </svg>
                """.formatted(xml(title));
        Files.writeString(output, svg, StandardCharsets.UTF_8);
    }

    private static void renderFlameChildren(StringBuilder svg, FlameNode node, int depth, double x,
                                            double baseY, double width, String units) {
        double childX = x;
        for (FlameNode child : node.childrenByWeight()) {
            double childWidth = width * child.value() / node.value();
            if (childWidth < 0.25) {
                childX += childWidth;
                continue;
            }

            double y = baseY - depth * FRAME_HEIGHT;
            String color = color(child.name());
            String label = child.name();
            svg.append("<g>\n<title>")
                    .append(xml(label)).append(": ")
                    .append(formatNumber(child.value())).append(' ').append(xml(units))
                    .append("</title>\n");
            svg.append("<rect class=\"frame\" x=\"").append(formatDouble(childX))
                    .append("\" y=\"").append(formatDouble(y))
                    .append("\" width=\"").append(formatDouble(childWidth))
                    .append("\" height=\"").append(FRAME_HEIGHT - 1)
                    .append("\" fill=\"").append(color).append("\"/>\n");
            if (childWidth >= 46) {
                svg.append("<text x=\"").append(formatDouble(childX + 4))
                        .append("\" y=\"").append(formatDouble(y + 13))
                        .append("\">").append(xml(truncate(label, childWidth))).append("</text>\n");
            }
            svg.append("</g>\n");

            renderFlameChildren(svg, child, depth + 1, childX, baseY, childWidth, units);
            childX += childWidth;
        }
    }

    private static String truncate(String label, double width) {
        int maxCharacters = Math.max(1, (int) ((width - 8) / 7));
        if (label.length() <= maxCharacters) {
            return label;
        }
        if (maxCharacters <= 1) {
            return "";
        }
        return label.substring(0, maxCharacters - 1) + ".";
    }

    private static String color(String value) {
        int hash = Math.abs(value.hashCode());
        int hue = 18 + hash % 300;
        int saturation = 55 + hash % 25;
        int lightness = 62 + hash % 12;
        return "hsl(" + hue + "," + saturation + "%," + lightness + "%)";
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static void writeSummary(Path output, Path runDirectory, List<TestRecord> tests,
                                     List<ModuleSummary> modules, JfrReport jfr, int topLimit)
            throws IOException {
        List<TestRecord> nonSuccessful = tests.stream()
                .filter(record -> !"SUCCESSFUL".equals(record.status()))
                .toList();
        List<TestRecord> slowest = tests.stream()
                .sorted(Comparator.comparingLong(TestRecord::durationMillis).reversed())
                .limit(topLimit)
                .toList();

        StringBuilder markdown = new StringBuilder();
        markdown.append("# Orion Test Analytics\n\n");
        markdown.append("- Generated: ").append(Instant.now()).append('\n');
        markdown.append("- Run directory: `").append(runDirectory).append("`\n");
        markdown.append("- Run IDs: ").append(runIds(tests)).append('\n');
        markdown.append("- Tests: ").append(tests.size()).append('\n');
        markdown.append("- Successful: ").append(countStatus(tests, "SUCCESSFUL")).append('\n');
        markdown.append("- Failed: ").append(countStatus(tests, "FAILED")).append('\n');
        markdown.append("- Aborted: ").append(countStatus(tests, "ABORTED")).append('\n');
        markdown.append("- Skipped: ").append(countStatus(tests, "SKIPPED")).append('\n');
        markdown.append("- Sum of test durations: ").append(formatMillis(sumDurations(tests))).append('\n');
        markdown.append("- JFR files: ").append(jfr.files().size()).append('\n');
        markdown.append("- CPU sample stacks: ").append(jfr.cpuStacks().size()).append('\n');
        markdown.append("- Allocation sample stacks: ").append(jfr.allocationStacks().size()).append("\n\n");

        markdown.append("## Artifacts\n\n");
        markdown.append("- `tests.csv`\n");
        markdown.append("- `modules.csv`\n");
        markdown.append("- `allocations.csv`\n");
        markdown.append("- `byte-array-allocations.csv`\n");
        markdown.append("- `jfr-events.csv`\n");
        markdown.append("- `flamegraph-cpu.svg`\n");
        markdown.append("- `flamegraph-alloc.svg`\n");
        markdown.append("- raw JFR files under `jfr/`\n\n");

        appendModuleTable(markdown, modules);
        appendAllocationTable(markdown, "Top Allocation Hotspots", jfr.allocationHotspots(), topLimit);
        appendAllocationTable(markdown, "Byte Array Allocation Hotspots",
                byteArrayAllocations(jfr.allocationHotspots()), topLimit);
        appendSlowestTests(markdown, slowest);
        appendNonSuccessfulTests(markdown, nonSuccessful, topLimit);
        appendJfrEvents(markdown, jfr.eventCounts(), topLimit);
        appendUnreadableFiles(markdown, jfr.unreadableFiles());

        Files.writeString(output, markdown.toString(), StandardCharsets.UTF_8);
    }

    private static void writeHtmlReport(Path output, Path runDirectory, List<TestRecord> tests,
                                        List<ModuleSummary> modules, JfrReport jfr)
            throws IOException {
        List<TestRecord> nonSuccessful = tests.stream()
                .filter(record -> !"SUCCESSFUL".equals(record.status()))
                .toList();
        List<TestRecord> sortedTests = tests.stream()
                .sorted(Comparator.comparingLong(TestRecord::durationMillis).reversed())
                .toList();
        List<AllocationStats> byteArrayAllocations = byteArrayAllocations(jfr.allocationHotspots());

        StringBuilder html = new StringBuilder(96_000);
        html.append("""
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Orion Test Analytics</title>
                <style>
                :root { color-scheme: light; --border: #d7dde5; --muted: #56616f; --bg: #f7f8fa; }
                body { margin: 0; font: 14px/1.45 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
                main { max-width: 1280px; margin: 0 auto; padding: 24px; }
                h1 { margin: 0 0 8px; font-size: 28px; }
                h2 { margin: 32px 0 12px; font-size: 20px; }
                code { font-family: Menlo, Consolas, monospace; font-size: 12px; }
                .muted { color: var(--muted); }
                .metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 8px; }
                .metric { border: 1px solid var(--border); padding: 10px 12px; background: var(--bg); }
                .metric strong { display: block; font-size: 20px; }
                .artifacts { display: flex; flex-wrap: wrap; gap: 8px; padding: 0; list-style: none; }
                .artifacts a { display: block; border: 1px solid var(--border); padding: 6px 9px; color: #0f4c81; }
                .table-wrap { overflow-x: auto; border: 1px solid var(--border); }
                table { border-collapse: collapse; min-width: 100%; }
                th, td { border-bottom: 1px solid var(--border); padding: 7px 9px; text-align: left; vertical-align: top; }
                th { background: var(--bg); cursor: pointer; position: sticky; top: 0; user-select: none; }
                th::after { content: " \\2195"; color: #7b8794; font-size: 11px; }
                td.num, th.num { text-align: right; white-space: nowrap; }
                .stack-cell { min-width: 760px; max-width: 1200px; width: 60%; }
                .stack-cell code { overflow-wrap: anywhere; white-space: normal; }
                .stack-frame { display: block; }
                object { width: 100%; min-height: 520px; border: 1px solid var(--border); background: white; }
                </style>
                </head>
                <body>
                <main>
                """);
        html.append("<h1>Orion Test Analytics</h1>\n");
        html.append("<p class=\"muted\">Generated ").append(xml(Instant.now().toString()))
                .append(" for <code>").append(xml(runDirectory.toString())).append("</code></p>\n");
        appendHtmlMetrics(html, tests, jfr);
        appendHtmlArtifacts(html);
        appendHtmlAllocationTable(html, "Byte Array Allocations", byteArrayAllocations);
        appendHtmlAllocationTable(html, "All Allocations", jfr.allocationHotspots());
        appendHtmlObject(html, "Allocation Flame Graph", "flamegraph-alloc.svg");
        appendHtmlObject(html, "CPU Flame Graph", "flamegraph-cpu.svg");
        appendHtmlModuleTable(html, "Modules", modules);
        appendHtmlTestsTable(html, "Tests", sortedTests);
        appendHtmlTestsTable(html, "Non-Successful Tests", nonSuccessful);
        appendHtmlJfrEvents(html, jfr.eventCounts());
        appendHtmlSortingScript(html);
        html.append("</main>\n</body>\n</html>\n");

        Files.writeString(output, html.toString(), StandardCharsets.UTF_8);
    }

    private static void appendHtmlMetrics(StringBuilder html, List<TestRecord> tests, JfrReport jfr) {
        html.append("<section class=\"metrics\">\n");
        appendHtmlMetric(html, "Tests", Integer.toString(tests.size()));
        appendHtmlMetric(html, "Successful", Long.toString(countStatus(tests, "SUCCESSFUL")));
        appendHtmlMetric(html, "Failed", Long.toString(countStatus(tests, "FAILED")));
        appendHtmlMetric(html, "Skipped", Long.toString(countStatus(tests, "SKIPPED")));
        appendHtmlMetric(html, "Duration Sum", formatMillis(sumDurations(tests)));
        appendHtmlMetric(html, "JFR Files", Integer.toString(jfr.files().size()));
        appendHtmlMetric(html, "CPU Stacks", Integer.toString(jfr.cpuStacks().size()));
        appendHtmlMetric(html, "Allocation Stacks", Integer.toString(jfr.allocationStacks().size()));
        html.append("</section>\n");
    }

    private static void appendHtmlMetric(StringBuilder html, String label, String value) {
        html.append("<div class=\"metric\"><span>")
                .append(xml(label))
                .append("</span><strong>")
                .append(xml(value))
                .append("</strong></div>\n");
    }

    private static void appendHtmlArtifacts(StringBuilder html) {
        html.append("<h2>Artifacts</h2>\n<ul class=\"artifacts\">\n");
        for (String artifact : List.of("summary.md", "tests.csv", "modules.csv", "allocations.csv",
                "byte-array-allocations.csv", "jfr-events.csv", "flamegraph-alloc.svg", "flamegraph-cpu.svg")) {
            html.append("<li><a href=\"").append(xml(artifact)).append("\">")
                    .append(xml(artifact)).append("</a></li>\n");
        }
        html.append("</ul>\n<p class=\"muted\">Raw JFR files are under <code>jfr/</code>.</p>\n");
    }

    private static void appendHtmlObject(StringBuilder html, String title, String href) {
        html.append("<h2>").append(xml(title)).append("</h2>\n");
        html.append("<object data=\"").append(xml(href)).append("\" type=\"image/svg+xml\"></object>\n");
    }

    private static void appendHtmlModuleTable(StringBuilder html, String title, List<ModuleSummary> modules) {
        html.append("<h2>").append(xml(title)).append("</h2>\n<div class=\"table-wrap\"><table class=\"sortable\">\n");
        html.append("<thead><tr><th>Module</th><th class=\"num\">Tests</th><th class=\"num\">OK</th>");
        html.append("<th class=\"num\">Failed</th><th class=\"num\">Skipped</th><th class=\"num\">Total</th>");
        html.append("<th class=\"num\">Avg</th><th class=\"num\">Max</th></tr></thead><tbody>\n");
        for (ModuleSummary module : modules) {
            html.append("<tr><td><code>").append(xml(module.module())).append("</code></td>");
            appendHtmlNumberCell(html, module.total(), Integer.toString(module.total()));
            appendHtmlNumberCell(html, module.successful(), Integer.toString(module.successful()));
            appendHtmlNumberCell(html, module.failed(), Integer.toString(module.failed()));
            appendHtmlNumberCell(html, module.skipped(), Integer.toString(module.skipped()));
            appendHtmlNumberCell(html, module.totalMillis(), formatMillis(module.totalMillis()));
            appendHtmlNumberCell(html, module.averageMillis(), formatMillis(module.averageMillis()));
            appendHtmlNumberCell(html, module.maxMillis(), formatMillis(module.maxMillis()));
            html.append("</tr>\n");
        }
        html.append("</tbody></table></div>\n");
    }

    private static void appendHtmlAllocationTable(StringBuilder html, String title,
                                                  List<AllocationStats> allocations) {
        html.append("<h2>").append(xml(title)).append("</h2>\n");
        if (allocations.isEmpty()) {
            html.append("<p class=\"muted\">No allocation stack samples found.</p>\n");
            return;
        }
        html.append("<div class=\"table-wrap\"><table class=\"sortable\">\n");
        html.append("<thead><tr><th class=\"num\">Bytes</th><th class=\"num\">Samples</th>");
        html.append("<th>Object</th><th>Top Frame</th><th>Stack</th></tr></thead><tbody>\n");
        for (AllocationStats allocation : allocations) {
            html.append("<tr>");
            appendHtmlNumberCell(html, allocation.bytes(), formatBytes(allocation.bytes()));
            appendHtmlNumberCell(html, allocation.samples(), Long.toString(allocation.samples()));
            html.append("<td><code>").append(xml(allocation.objectClass())).append("</code></td><td><code>")
                    .append(xml(allocation.topFrame())).append("</code></td>");
            appendHtmlStackCell(html, allocation.stack());
            html.append("</tr>\n");
        }
        html.append("</tbody></table></div>\n");
    }

    static void appendHtmlStackCell(StringBuilder html, String stack) {
        html.append("<td class=\"stack-cell\"><code>");
        String[] frames = stack.split(";", -1);
        for (String frame : frames) {
            if (!frame.isEmpty()) {
                html.append("<span class=\"stack-frame\">").append(xml(frame)).append("</span>");
            }
        }
        html.append("</code></td>");
    }

    private static void appendHtmlTestsTable(StringBuilder html, String title, List<TestRecord> tests) {
        html.append("<h2>").append(xml(title)).append("</h2>\n");
        if (tests.isEmpty()) {
            html.append("<p class=\"muted\">None.</p>\n");
            return;
        }
        html.append("<div class=\"table-wrap\"><table class=\"sortable\">\n");
        html.append("<thead><tr><th class=\"num\">Duration</th><th>Status</th><th>Module</th>");
        html.append("<th>Test</th><th>Reason</th></tr></thead><tbody>\n");
        for (TestRecord test : tests) {
            html.append("<tr>");
            appendHtmlNumberCell(html, test.durationMillis(), formatMillis(test.durationMillis()));
            html.append("<td>").append(xml(test.status())).append("</td><td><code>")
                    .append(xml(test.module())).append("</code></td><td><code>")
                    .append(xml(test.testId())).append("</code></td><td>")
                    .append(xml(test.reason())).append("</td></tr>\n");
        }
        html.append("</tbody></table></div>\n");
    }

    private static void appendHtmlJfrEvents(StringBuilder html, Map<String, Long> eventCounts) {
        html.append("<h2>JFR Events</h2>\n");
        if (eventCounts.isEmpty()) {
            html.append("<p class=\"muted\">No readable JFR events found.</p>\n");
            return;
        }
        Comparator<Map.Entry<String, Long>> byCountThenName =
                Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey());
        html.append("<div class=\"table-wrap\"><table class=\"sortable\">\n");
        html.append("<thead><tr><th>Event</th><th class=\"num\">Count</th></tr></thead><tbody>\n");
        eventCounts.entrySet().stream().sorted(byCountThenName)
                .forEach(entry -> html.append("<tr><td><code>").append(xml(entry.getKey()))
                        .append("</code></td>")
                        .append(htmlNumberCell(entry.getValue(), Long.toString(entry.getValue())))
                        .append("</tr>\n"));
        html.append("</tbody></table></div>\n");
    }

    private static void appendHtmlNumberCell(StringBuilder html, long sortValue, String displayValue) {
        html.append(htmlNumberCell(sortValue, displayValue));
    }

    private static String htmlNumberCell(long sortValue, String displayValue) {
        return "<td class=\"num\" data-sort=\"" + sortValue + "\">" + xml(displayValue) + "</td>";
    }

    private static void appendHtmlSortingScript(StringBuilder html) {
        html.append("""
                <script>
                document.querySelectorAll("table.sortable th").forEach((th, index) => {
                  th.addEventListener("click", () => {
                    const table = th.closest("table");
                    const tbody = table.tBodies[0];
                    const rows = Array.from(tbody.rows);
                    const direction = th.dataset.direction === "asc" ? -1 : 1;
                    table.querySelectorAll("th").forEach(header => delete header.dataset.direction);
                    th.dataset.direction = direction === 1 ? "asc" : "desc";
                    rows.sort((left, right) => compareCells(left.cells[index], right.cells[index]) * direction);
                    rows.forEach(row => tbody.appendChild(row));
                  });
                });
                function compareCells(left, right) {
                  const leftValue = left.dataset.sort ?? left.textContent.trim();
                  const rightValue = right.dataset.sort ?? right.textContent.trim();
                  const leftNumber = Number(leftValue);
                  const rightNumber = Number(rightValue);
                  if (!Number.isNaN(leftNumber) && !Number.isNaN(rightNumber)) {
                    return leftNumber - rightNumber;
                  }
                  return leftValue.localeCompare(rightValue);
                }
                </script>
                """);
    }

    private static String runIds(List<TestRecord> tests) {
        if (tests.isEmpty()) {
            return "(none)";
        }
        return tests.stream()
                .map(TestRecord::runId)
                .filter(runId -> !runId.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static void appendModuleTable(StringBuilder markdown, List<ModuleSummary> modules) {
        markdown.append("## Modules\n\n");
        markdown.append("| Module | Tests | OK | Failed | Aborted | Skipped | Total | Avg | Max |\n");
        markdown.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (ModuleSummary module : modules) {
            markdown.append("| `").append(module.module()).append("` | ")
                    .append(module.total()).append(" | ")
                    .append(module.successful()).append(" | ")
                    .append(module.failed()).append(" | ")
                    .append(module.aborted()).append(" | ")
                    .append(module.skipped()).append(" | ")
                    .append(formatMillis(module.totalMillis())).append(" | ")
                    .append(formatMillis(module.averageMillis())).append(" | ")
                    .append(formatMillis(module.maxMillis())).append(" |\n");
        }
        markdown.append('\n');
    }

    private static void appendSlowestTests(StringBuilder markdown, List<TestRecord> tests) {
        markdown.append("## Slowest Tests\n\n");
        markdown.append("| Duration | Status | Module | Test |\n");
        markdown.append("| ---: | --- | --- | --- |\n");
        for (TestRecord test : tests) {
            markdown.append("| ").append(formatMillis(test.durationMillis())).append(" | ")
                    .append(test.status()).append(" | `")
                    .append(test.module()).append("` | `")
                    .append(test.testId()).append("` |\n");
        }
        markdown.append('\n');
    }

    private static void appendAllocationTable(StringBuilder markdown, String title,
                                              List<AllocationStats> allocations, int topLimit) {
        markdown.append("## ").append(title).append("\n\n");
        if (allocations.isEmpty()) {
            markdown.append("No allocation stack samples found.\n\n");
            return;
        }

        markdown.append("| Bytes | Samples | Object | Top Frame |\n");
        markdown.append("| ---: | ---: | --- | --- |\n");
        allocations.stream().limit(topLimit).forEach(allocation -> markdown.append("| ")
                .append(formatBytes(allocation.bytes())).append(" | ")
                .append(allocation.samples()).append(" | `")
                .append(allocation.objectClass()).append("` | `")
                .append(allocation.topFrame()).append("` |\n"));
        markdown.append('\n');
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1024.0 / 1024.0);
    }

    private static void appendNonSuccessfulTests(StringBuilder markdown, List<TestRecord> tests, int topLimit) {
        markdown.append("## Non-Successful Tests\n\n");
        if (tests.isEmpty()) {
            markdown.append("None.\n\n");
            return;
        }

        markdown.append("| Status | Module | Test | Reason |\n");
        markdown.append("| --- | --- | --- | --- |\n");
        tests.stream().limit(topLimit).forEach(test -> markdown.append("| ")
                .append(test.status()).append(" | `")
                .append(test.module()).append("` | `")
                .append(test.testId()).append("` | ")
                .append(markdownCell(test.reason())).append(" |\n"));
        markdown.append('\n');
    }

    private static void appendJfrEvents(StringBuilder markdown, Map<String, Long> eventCounts, int topLimit) {
        markdown.append("## JFR Events\n\n");
        if (eventCounts.isEmpty()) {
            markdown.append("No readable JFR events found.\n\n");
            return;
        }

        markdown.append("| Event | Count |\n");
        markdown.append("| --- | ---: |\n");
        Comparator<Map.Entry<String, Long>> byCountThenName =
                Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey());
        eventCounts.entrySet().stream()
                .sorted(byCountThenName)
                .limit(topLimit)
                .forEach(entry -> markdown.append("| `").append(entry.getKey()).append("` | ")
                        .append(entry.getValue()).append(" |\n"));
        markdown.append('\n');
    }

    private static void appendUnreadableFiles(StringBuilder markdown, Map<Path, String> unreadableFiles) {
        if (unreadableFiles.isEmpty()) {
            return;
        }

        markdown.append("## Unreadable JFR Files\n\n");
        unreadableFiles.forEach((file, error) -> markdown.append("- `")
                .append(file).append("`: ")
                .append(markdownCell(error)).append('\n'));
        markdown.append('\n');
    }

    private static long countStatus(List<TestRecord> tests, String status) {
        return tests.stream().filter(test -> status.equals(test.status())).count();
    }

    private static long sumDurations(List<TestRecord> tests) {
        return tests.stream().mapToLong(TestRecord::durationMillis).sum();
    }

    private static String formatMillis(long millis) {
        if (millis < 1_000) {
            return millis + " ms";
        }
        return String.format(Locale.ROOT, "%.3f s", millis / 1_000.0);
    }

    private static String formatNumber(long number) {
        return String.format(Locale.ROOT, "%,d", number);
    }

    private static String csv(String... values) {
        return Stream.of(values)
                .map(TestAnalyticsReport::csvValue)
                .collect(Collectors.joining(","));
    }

    private static String csvValue(String value) {
        String safeValue = Objects.toString(value, "");
        if (safeValue.contains(",") || safeValue.contains("\"") || safeValue.contains("\n")) {
            return "\"" + safeValue.replace("\"", "\"\"") + "\"";
        }
        return safeValue;
    }

    private static String markdownCell(String value) {
        return Objects.toString(value, "")
                .replace("\n", " ")
                .replace("|", "\\|");
    }

    private static String xml(String value) {
        return Objects.toString(value, "")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    record ReportFiles(Path html, Path summary, Path testsCsv, Path modulesCsv, Path allocationsCsv,
                       Path byteArrayAllocationsCsv, Path jfrEventsCsv, Path cpuFlameGraph,
                       Path allocationFlameGraph) {
    }

    private record TestRecord(String runId, String module, String testId, String className, String methodName,
                              String displayName, String status, long durationMillis, String startedAt,
                              String finishedAt, String reason) {
    }

    private record ModuleSummary(String module, int total, int successful, int failed, int aborted, int skipped,
                                 long totalMillis, long averageMillis, long maxMillis) {
    }

    private record JfrReport(List<Path> files, Map<Path, String> unreadableFiles, Map<String, Long> eventCounts,
                             Map<String, Long> cpuStacks, Map<String, Long> allocationStacks,
                             List<AllocationStats> allocationHotspots) {
    }

    private record AllocationKey(String eventName, String objectClass, String stack) {
    }

    private static final class AllocationStats {
        private static final Comparator<AllocationStats> ORDER = Comparator
                .comparingLong(AllocationStats::bytes)
                .reversed()
                .thenComparing(AllocationStats::objectClass)
                .thenComparing(AllocationStats::topFrame);

        private final AllocationKey key;
        private long samples;
        private long bytes;

        private AllocationStats(AllocationKey key) {
            this.key = key;
        }

        private void add(long bytes) {
            samples++;
            this.bytes += bytes;
        }

        private String eventName() {
            return key.eventName();
        }

        private String objectClass() {
            return key.objectClass();
        }

        private String stack() {
            return key.stack();
        }

        private String topFrame() {
            int offset = key.stack().lastIndexOf(';');
            return offset < 0 ? key.stack() : key.stack().substring(offset + 1);
        }

        private long samples() {
            return samples;
        }

        private long bytes() {
            return bytes;
        }
    }

    private static final class ModuleSummaryBuilder {
        private final String module;
        private int total;
        private int successful;
        private int failed;
        private int aborted;
        private int skipped;
        private long totalMillis;
        private long maxMillis;

        private ModuleSummaryBuilder(String module) {
            this.module = module;
        }

        private void add(TestRecord record) {
            total++;
            totalMillis += record.durationMillis();
            maxMillis = Math.max(maxMillis, record.durationMillis());
            switch (record.status()) {
                case "SUCCESSFUL" -> successful++;
                case "FAILED" -> failed++;
                case "ABORTED" -> aborted++;
                case "SKIPPED" -> skipped++;
                default -> {
                }
            }
        }

        private ModuleSummary build() {
            long averageMillis = total == 0 ? 0 : totalMillis / total;
            return new ModuleSummary(module, total, successful, failed, aborted, skipped, totalMillis,
                    averageMillis, maxMillis);
        }
    }

    private static final class FlameNode {
        private final String name;
        private final Map<String, FlameNode> children = new LinkedHashMap<>();
        private long value;

        private FlameNode(String name) {
            this.name = name;
        }

        private String name() {
            return name;
        }

        private long value() {
            return value;
        }

        private void add(String[] stack, long weight) {
            value += weight;
            FlameNode current = this;
            for (String frame : stack) {
                if (frame.isBlank()) {
                    continue;
                }
                current = current.children.computeIfAbsent(frame, FlameNode::new);
                current.value += weight;
            }
        }

        private int depth() {
            int max = 0;
            ArrayDeque<NodeDepth> stack = new ArrayDeque<>();
            stack.push(new NodeDepth(this, 0));
            while (!stack.isEmpty()) {
                NodeDepth current = stack.pop();
                max = Math.max(max, current.depth());
                for (FlameNode child : current.node().children.values()) {
                    stack.push(new NodeDepth(child, current.depth() + 1));
                }
            }
            return max;
        }

        private List<FlameNode> childrenByWeight() {
            Comparator<FlameNode> byWeightThenName = Comparator.comparingLong(FlameNode::value)
                    .reversed()
                    .thenComparing(FlameNode::name);
            return children.values().stream()
                    .sorted(byWeightThenName)
                    .toList();
        }
    }

    private record NodeDepth(FlameNode node, int depth) {
    }
}
