package pro.deta.orion.command.terminal;

import pro.deta.orion.command.CommandPath;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TerminalDisplay {
    private static final String CLEAR_LINE = "\u001b[2K";
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private final OutputStream output;
    private final boolean ansi;
    private final ReentrantLock outputLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile int columns;
    private int previousFrameWidth;
    private Prompt lastPrompt;

    private record Prompt(String prompt, String line, int cursor) {
    }

    private record Cell(String text, int width, int end) {
    }

    public TerminalDisplay(OutputStream output, boolean ansi, int columns) {
        this.output = Objects.requireNonNull(output, "output");
        this.ansi = ansi;
        if (columns < 0) {
            throw new IllegalArgumentException("columns must not be negative");
        }
        this.columns = columns;
    }

    int columns() {
        return columns;
    }

    public static String prompt(String username, CommandPath path) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(path, "path");
        String location = path.segments().isEmpty() ? "" : " " + path;
        return "[" + username + "@orion" + location + "] > ";
    }

    public void redraw(String prompt, String line, int cursor) throws IOException {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(line, "line");
        int codePoints = line.codePointCount(0, line.length());
        if (cursor < 0 || cursor > codePoints) {
            throw new IllegalArgumentException("cursor is outside line");
        }
        outputLock.lock();
        try {
            redrawLocked(new Prompt(prompt, line, cursor));
        } finally {
            outputLock.unlock();
        }
    }

    public void resize(int columns) throws IOException {
        if (columns < 0) {
            throw new IllegalArgumentException("columns must not be negative");
        }
        outputLock.lock();
        try {
            this.columns = columns;
            if (lastPrompt != null) {
                redrawLocked(lastPrompt);
            }
        } finally {
            outputLock.unlock();
        }
    }

    private void redrawLocked(Prompt prompt) throws IOException {
        int columns = this.columns == 0 ? 80 : this.columns;
        int available = columns - 1;
        List<Cell> prefix = cells(prompt.prompt());
        int prefixWidth = 0;
        for (Cell cell : prefix) {
            prefixWidth += cell.width();
        }
        StringBuilder visible = new StringBuilder();
        int prefixLimit = available / 2;
        if (prefixWidth > prefixLimit) {
            prefixWidth = 0;
            for (Cell cell : prefix) {
                if (prefixWidth + cell.width() >= prefixLimit) {
                    break;
                }
                visible.append(cell.text());
                prefixWidth += cell.width();
            }
            if (prefixLimit > 0) {
                visible.append('~');
                prefixWidth++;
            }
        } else {
            for (Cell cell : prefix) {
                visible.append(cell.text());
            }
        }
        List<Cell> text = cells(prompt.line());
        int characterCursor = prompt.line().offsetByCodePoints(0, prompt.cursor());
        int caret = 0;
        int beforeCursor = 0;
        int characterEnd = 0;
        while (caret < text.size() && characterEnd < characterCursor) {
            Cell cell = text.get(caret++);
            beforeCursor += cell.width();
            characterEnd = cell.end();
        }
        int inputWidth = available - prefixWidth;
        int start = 0;
        while (start < caret && beforeCursor > Math.max(0, inputWidth - 1)) {
            beforeCursor -= text.get(start++).width();
        }
        int renderedWidth = prefixWidth;
        for (int index = start; index < text.size(); index++) {
            Cell cell = text.get(index);
            if (renderedWidth + cell.width() > available) {
                break;
            }
            visible.append(cell.text());
            renderedWidth += cell.width();
        }
        int cursor = prefixWidth + beforeCursor;
        StringBuilder frame = new StringBuilder("\r");
        if (ansi) {
            frame.append(CLEAR_LINE);
        } else if (previousFrameWidth > 0) {
            frame.append(" ".repeat(Math.min(previousFrameWidth, available))).append('\r');
        }
        frame.append(visible);
        int moveLeft = renderedWidth - cursor;
        if (moveLeft > 0) {
            if (ansi) {
                frame.append("\u001b[").append(moveLeft).append('D');
            } else {
                frame.append("\b".repeat(moveLeft));
            }
        }
        writeLocked(frame.toString());
        previousFrameWidth = renderedWidth;
        lastPrompt = prompt;
    }

    private static List<Cell> cells(String value) {
        List<Cell> result = new ArrayList<>();
        Matcher matcher = GRAPHEME.matcher(value);
        while (matcher.find()) {
            String cluster = matcher.group();
            StringBuilder safe = new StringBuilder();
            for (int offset = 0; offset < cluster.length();) {
                int point = cluster.codePointAt(offset);
                offset += Character.charCount(point);
                if (Character.isISOControl(point)) {
                    safe.setLength(0);
                    safe.append('\uFFFD');
                    break;
                }
                safe.appendCodePoint(point);
            }
            String text = safe.toString();
            int width = TerminalCellWidth.width(text);
            if (width == 0) {
                text = "◌" + text;
                width = 1;
            }
            result.add(new Cell(text, width, matcher.end()));
        }
        return result;
    }

    public void write(String value) throws IOException {
        outputLock.lock();
        try {
            writeLocked(value);
            if (!value.isEmpty() && !value.equals("\u0007")) {
                lastPrompt = null;
                previousFrameWidth = 0;
            }
        } finally {
            outputLock.unlock();
        }
    }

    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            output.close();
        }
    }

    private void writeLocked(String value) throws IOException {
        if (closed.get()) {
            throw new IOException("terminal display is closed");
        }
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    public boolean ansi() {
        return ansi;
    }

    public static String columns(List<String> values, int width) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            return "";
        }
        int longest = 0;
        for (String value : values) {
            longest = Math.max(longest, value.length());
        }
        int cellWidth = longest + 2;
        int columnCount = Math.max(1, width / cellWidth);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            boolean rowEnd = (index + 1) % columnCount == 0 || index == values.size() - 1;
            result.append(value);
            if (rowEnd) {
                result.append('\n');
            } else {
                result.append(" ".repeat(cellWidth - value.length()));
            }
        }
        return result.toString();
    }
}
