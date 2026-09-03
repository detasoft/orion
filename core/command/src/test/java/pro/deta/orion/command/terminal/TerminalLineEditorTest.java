package pro.deta.orion.command.terminal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalLineEditorTest {
    private final TerminalLineEditor editor = new TerminalLineEditor(3, 64);

    @Test
    void editsUnicodeByCodePointAndAcceptsSplitEscapeSequences() {
        accept("a🙂c");
        accept(new byte[]{0x1b, '['});
        accept(new byte[]{'D', 0x7f});
        accept("β");

        assertThat(editor.line()).isEqualTo("aβc");
        assertThat(editor.cursor()).isEqualTo(2);

        accept(new byte[]{0x1b, '[', 'H'});
        accept("x");
        accept(new byte[]{0x1b, '[', 'F', 0x1b, '[', '3', '~'});

        assertThat(editor.line()).isEqualTo("xaβc");
        assertThat(editor.cursor()).isEqualTo(4);
    }

    @Test
    void emitsSubmitCompletionCancellationAndEofEvents() {
        assertThat(accept("show\r")).containsExactly(
                TerminalInputEvent.Redraw.INSTANCE,
                TerminalInputEvent.Redraw.INSTANCE,
                TerminalInputEvent.Redraw.INSTANCE,
                TerminalInputEvent.Redraw.INSTANCE,
                new TerminalInputEvent.Submit("show"));
        assertThat(accept("list\n")).containsExactly(
                TerminalInputEvent.Redraw.INSTANCE,
                TerminalInputEvent.Redraw.INSTANCE,
                TerminalInputEvent.Redraw.INSTANCE,
                TerminalInputEvent.Redraw.INSTANCE,
                new TerminalInputEvent.Submit("list"));
        assertThat(accept(new byte[]{'x', '\t'})).containsExactly(
                TerminalInputEvent.Redraw.INSTANCE,
                TerminalInputEvent.Complete.INSTANCE);
        assertThat(accept(new byte[]{3, 4})).containsExactly(
                TerminalInputEvent.Cancel.INSTANCE,
                TerminalInputEvent.EndOfInput.INSTANCE);
    }

    @Test
    void traversesBoundedHistoryWithoutDuplicatingAdjacentCommands() {
        submit("one");
        submit("two");
        submit("two");
        submit("three");
        submit("four");

        accept(new byte[]{0x1b, '[', 'A', 0x1b, '[', 'A', 0x1b, '[', 'A', 0x1b, '[', 'A'});
        assertThat(editor.line()).isEqualTo("two");
        accept(new byte[]{0x1b, '[', 'B'});
        assertThat(editor.line()).isEqualTo("three");
    }

    @Test
    void decodesUtf8AcrossChunksAndEnforcesMaximumLineLength() {
        byte[] smile = "🙂".getBytes(StandardCharsets.UTF_8);
        accept(new byte[]{smile[0], smile[1]});
        accept(new byte[]{smile[2], smile[3]});
        accept("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnop");

        assertThat(editor.line().codePoints()).hasSize(64);
        assertThat(editor.line()).startsWith("🙂abc");
    }

    @Test
    void truncatesCompletionAndClampsItsCursorAtTheLineLimit() {
        editor.replace("x".repeat(65), 65);

        assertThat(editor.line()).hasSize(64);
        assertThat(editor.cursor()).isEqualTo(64);
    }

    private void submit(String value) {
        accept(value + "\r");
    }

    private List<TerminalInputEvent> accept(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return accept(bytes);
    }

    private List<TerminalInputEvent> accept(byte[] bytes) {
        return editor.accept(bytes, 0, bytes.length);
    }
}
