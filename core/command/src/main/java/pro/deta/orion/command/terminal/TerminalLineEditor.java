package pro.deta.orion.command.terminal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class TerminalLineEditor {
    private final int historyLimit;
    private final int lineLimit;
    private final List<Integer> value = new ArrayList<>();
    private final List<String> history = new ArrayList<>();
    private final byte[] utf8 = new byte[4];
    private int cursor;
    private int historyIndex;
    private int escapeState;
    private int utf8Length;
    private int utf8Expected;
    private boolean ignoreLineFeed;

    public TerminalLineEditor(int historyLimit, int lineLimit) {
        if (historyLimit < 1 || lineLimit < 1) {
            throw new IllegalArgumentException("editor limits must be positive");
        }
        this.historyLimit = historyLimit;
        this.lineLimit = lineLimit;
    }

    public List<TerminalInputEvent> accept(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IndexOutOfBoundsException("invalid input range");
        }
        List<TerminalInputEvent> events = new ArrayList<>();
        for (int index = offset; index < offset + length; index++) {
            process(bytes[index] & 0xff, events);
        }
        return List.copyOf(events);
    }

    public String line() {
        StringBuilder text = new StringBuilder();
        for (int codePoint : value) {
            text.appendCodePoint(codePoint);
        }
        return text.toString();
    }

    public int cursor() {
        return cursor;
    }

    public void replace(String line, int newCursor) {
        int suppliedLength = line.codePointCount(0, line.length());
        if (newCursor < 0 || newCursor > suppliedLength) {
            throw new IllegalArgumentException("cursor is outside line");
        }
        int[] codePoints = line.codePoints().limit(lineLimit).toArray();
        value.clear();
        for (int codePoint : codePoints) {
            value.add(codePoint);
        }
        cursor = Math.min(newCursor, codePoints.length);
        historyIndex = history.size();
    }

    public void clear() {
        value.clear();
        cursor = 0;
        historyIndex = history.size();
        utf8Length = 0;
        utf8Expected = 0;
        escapeState = 0;
    }

    private void process(int current, List<TerminalInputEvent> events) {
        if (ignoreLineFeed && current != '\n') {
            ignoreLineFeed = false;
        }
        if (escapeState != 0) {
            processEscape(current, events);
            return;
        }
        if (utf8Expected != 0 || current >= 0x80) {
            processUtf8(current, events);
            return;
        }
        if (current == 0x1b) {
            escapeState = 1;
        } else if (current == '\r' || current == '\n') {
            submit(current, events);
        } else if (current == 0x08 || current == 0x7f) {
            if (cursor > 0) {
                value.remove(--cursor);
                events.add(TerminalInputEvent.Redraw.INSTANCE);
            }
        } else if (current == '\t') {
            events.add(TerminalInputEvent.Complete.INSTANCE);
        } else if (current == 0x03) {
            clear();
            events.add(TerminalInputEvent.Cancel.INSTANCE);
        } else if (current == 0x04) {
            events.add(TerminalInputEvent.EndOfInput.INSTANCE);
        } else if (current >= 0x20) {
            insert(current, events);
        }
    }

    private void processEscape(int current, List<TerminalInputEvent> events) {
        if (escapeState == 1) {
            escapeState = current == '[' ? 2 : 0;
            return;
        }
        if (escapeState == 2 && current == '3') {
            escapeState = 3;
            return;
        }
        if (escapeState == 3) {
            escapeState = 0;
            if (current == '~' && cursor < value.size()) {
                value.remove(cursor);
                events.add(TerminalInputEvent.Redraw.INSTANCE);
            }
            return;
        }
        escapeState = 0;
        boolean changed = switch (current) {
            case 'A' -> previousHistory();
            case 'B' -> nextHistory();
            case 'C' -> moveRight();
            case 'D' -> moveLeft();
            case 'H' -> moveHome();
            case 'F' -> moveEnd();
            default -> false;
        };
        if (changed) {
            events.add(TerminalInputEvent.Redraw.INSTANCE);
        }
    }

    private void processUtf8(int current, List<TerminalInputEvent> events) {
        if (utf8Expected == 0) {
            utf8Expected = expectedUtf8Length(current);
            if (utf8Expected == 0) {
                return;
            }
        } else if ((current & 0xc0) != 0x80) {
            utf8Length = 0;
            utf8Expected = 0;
            process(current, events);
            return;
        }
        utf8[utf8Length++] = (byte) current;
        if (utf8Length == utf8Expected) {
            String decoded = new String(utf8, 0, utf8Length, StandardCharsets.UTF_8);
            decoded.codePoints().forEach(codePoint -> insert(codePoint, events));
            utf8Length = 0;
            utf8Expected = 0;
        }
    }

    private void submit(int terminator, List<TerminalInputEvent> events) {
        if (terminator == '\n' && ignoreLineFeed) {
            ignoreLineFeed = false;
            return;
        }
        ignoreLineFeed = terminator == '\r';
        String line = line();
        if (!line.isBlank() && (history.isEmpty() || !history.getLast().equals(line))) {
            history.add(line);
            if (history.size() > historyLimit) {
                history.removeFirst();
            }
        }
        clear();
        events.add(new TerminalInputEvent.Submit(line));
    }

    private void insert(int codePoint, List<TerminalInputEvent> events) {
        if (value.size() >= lineLimit) {
            return;
        }
        value.add(cursor++, codePoint);
        historyIndex = history.size();
        events.add(TerminalInputEvent.Redraw.INSTANCE);
    }

    private boolean previousHistory() {
        if (history.isEmpty()) {
            return false;
        }
        if (historyIndex > 0) {
            historyIndex--;
        }
        setFromHistory();
        return true;
    }

    private boolean nextHistory() {
        if (historyIndex >= history.size()) {
            return false;
        }
        historyIndex++;
        if (historyIndex == history.size()) {
            value.clear();
            cursor = 0;
        } else {
            setFromHistory();
        }
        return true;
    }

    private void setFromHistory() {
        value.clear();
        history.get(historyIndex).codePoints().forEach(value::add);
        cursor = value.size();
    }

    private boolean moveRight() {
        if (cursor >= value.size()) {
            return false;
        }
        cursor++;
        return true;
    }

    private boolean moveLeft() {
        if (cursor == 0) {
            return false;
        }
        cursor--;
        return true;
    }

    private boolean moveHome() {
        boolean changed = cursor != 0;
        cursor = 0;
        return changed;
    }

    private boolean moveEnd() {
        boolean changed = cursor != value.size();
        cursor = value.size();
        return changed;
    }

    private static int expectedUtf8Length(int first) {
        if ((first & 0xe0) == 0xc0) {
            return 2;
        }
        if ((first & 0xf0) == 0xe0) {
            return 3;
        }
        if ((first & 0xf8) == 0xf0) {
            return 4;
        }
        return 0;
    }
}
