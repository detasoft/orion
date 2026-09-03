package pro.deta.orion.command.terminal;

import java.util.Objects;

public sealed interface TerminalInputEvent {
    enum Redraw implements TerminalInputEvent {
        INSTANCE
    }

    record Submit(String line) implements TerminalInputEvent {
        public Submit {
            Objects.requireNonNull(line, "line");
        }
    }

    enum Complete implements TerminalInputEvent {
        INSTANCE
    }

    enum Cancel implements TerminalInputEvent {
        INSTANCE
    }

    enum EndOfInput implements TerminalInputEvent {
        INSTANCE
    }
}
