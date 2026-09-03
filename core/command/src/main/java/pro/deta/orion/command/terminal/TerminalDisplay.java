package pro.deta.orion.command.terminal;

import pro.deta.orion.command.CommandPath;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public final class TerminalDisplay {
    private static final String CLEAR_LINE = "\u001b[2K";
    private final OutputStream output;
    private final boolean ansi;
    private final ReentrantLock outputLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private int previousFrameWidth;

    public TerminalDisplay(OutputStream output, boolean ansi) {
        this.output = Objects.requireNonNull(output, "output");
        this.ansi = ansi;
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
            int moveLeft = codePoints - cursor;
            StringBuilder frame = new StringBuilder("\r");
            if (ansi) {
                frame.append(CLEAR_LINE);
            } else if (previousFrameWidth > 0) {
                frame.append(" ".repeat(previousFrameWidth)).append('\r');
            }
            frame.append(prompt).append(line);
            if (moveLeft > 0) {
                if (ansi) {
                    frame.append("\u001b[").append(moveLeft).append('D');
                } else {
                    frame.append("\b".repeat(moveLeft));
                }
            }
            writeLocked(frame.toString());
            previousFrameWidth = prompt.codePointCount(0, prompt.length()) + codePoints;
        } finally {
            outputLock.unlock();
        }
    }

    public void write(String value) throws IOException {
        outputLock.lock();
        try {
            writeLocked(value);
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
