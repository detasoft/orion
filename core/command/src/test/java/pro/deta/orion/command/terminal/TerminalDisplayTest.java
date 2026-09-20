package pro.deta.orion.command.terminal;

import org.junit.jupiter.api.Test;
import pro.deta.orion.command.CommandPath;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalDisplayTest {
    @Test
    void rendersRootAndNestedPrompts() {
        assertThat(TerminalDisplay.prompt("alice", CommandPath.root())).isEqualTo("[alice@orion] > ");
        assertThat(TerminalDisplay.prompt(
                "alice",
                CommandPath.absolute(List.of("organization", "acme"))))
                .isEqualTo("[alice@orion /organization/acme] > ");
    }

    @Test
    void gatesAnsiRedrawAndRestoresTheCursor() throws Exception {
        ByteArrayOutputStream ansiOutput = new ByteArrayOutputStream();
        TerminalDisplay ansi = new TerminalDisplay(ansiOutput, true, 80);
        ansi.redraw("prompt> ", "abcd", 2);
        assertThat(ansiOutput.toString(StandardCharsets.UTF_8))
                .isEqualTo("\r\u001b[2Kprompt> abcd\u001b[2D");

        ByteArrayOutputStream plainOutput = new ByteArrayOutputStream();
        TerminalDisplay plain = new TerminalDisplay(plainOutput, false, 80);
        plain.redraw("prompt> ", "abcd", 2);
        assertThat(plainOutput.toString(StandardCharsets.UTF_8))
                .isEqualTo("\rprompt> abcd\b\b");
        assertThat(plainOutput.toString(StandardCharsets.UTF_8)).doesNotContain("\u001b[");
    }

    @Test
    void plainRedrawErasesCharactersFromThePreviousLongerFrame() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TerminalDisplay display = new TerminalDisplay(output, false, 80);

        output.reset();
        display.redraw("prompt> ", "abc", 3);
        output.reset();
        display.redraw("prompt> ", "ab", 2);

        assertThat(output.toString(StandardCharsets.UTF_8))
                .isEqualTo("\r" + " ".repeat(11) + "\rprompt> ab");
    }

    @Test
    void scrollsLongInputAndKeepsTheCursorInsideTheRow() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TerminalDisplay display = new TerminalDisplay(output, true, 10);
        display.redraw("> ", "abcdefghijk", 11);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("\r\u001b[2K> fghijk");
        output.reset();
        display.redraw("> ", "abcdefghijk", 0);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("\r\u001b[2K> abcdefg\u001b[7D");
    }

    @Test
    void clipsLongPromptsToLeaveRoomForInput() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TerminalDisplay display = new TerminalDisplay(output, true, 10);
        display.redraw("a very long prompt> ", "abc", 3);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("\r\u001b[2Ka v~abc");
        output.reset();
        display.resize(1);
        output.reset();
        display.redraw("prompt> ", "界", 1);
        assertThat(output.toString(StandardCharsets.UTF_8)).endsWith("\r\u001b[2K");
    }

    @Test
    void positionsTheCursorByCellsAndKeepsCombiningSequencesTogether() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TerminalDisplay display = new TerminalDisplay(output, true, 20);
        display.redraw("> ", "界e\u0301文", 1);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("\r\u001b[2K> 界e\u0301文\u001b[3D");
        output.reset();
        display.redraw("> ", "界e\u0301文", 2);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("\r\u001b[2K> 界e\u0301文\u001b[2D");
        output.reset();
        display.resize(7);
        output.reset();
        display.redraw("> ", "界e\u0301文", 4);
        assertThat(output.toString(StandardCharsets.UTF_8)).endsWith("> e\u0301文");
    }

    @Test
    void keepsEmojiSequencesWholeAndUsesTwoCells() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TerminalDisplay display = new TerminalDisplay(output, true, 80);
        String line = "a👩‍💻🇳🇱b";
        display.resize(20);
        output.reset();
        display.redraw("> ", line, 1);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("\r\u001b[2K> " + line + "\u001b[5D");
    }

    @Test
    void resizeImmediatelyRedrawsTheViewportWithoutTouchingPreviousRows() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TerminalDisplay display = new TerminalDisplay(output, true, 20);
        display.redraw("> ", "abcdefghijk", 11);
        output.reset();
        display.resize(10);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("\r\u001b[2K> fghijk");
        output.reset();
        display.resize(20);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("\r\u001b[2K> abcdefghijk");
        output.reset();
        display.write("\r\ncommand output\r\n");
        output.reset();
        display.resize(8);
        assertThat(output.size()).isZero();
    }

    @Test
    void plainResizeDoesNotWrapWhileErasingTheOldFrame() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TerminalDisplay display = new TerminalDisplay(output, false, 20);
        display.redraw("> ", "abcdefghijk", 11);
        output.reset();
        display.resize(8);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("\r       \r> hijk");
    }

    @Test
    void rendersControlsAsTextAndUsesDefaultWidthWhenTheClientSuppliesZero() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TerminalDisplay display = new TerminalDisplay(output, true, 0);
        display.redraw("p\r\n> ", "x\u001by", 3);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("\r\u001b[2Kp�> x�y");
    }

    @Test
    void screenKeepsTextAndCursorWithinOneRowWhileEditingAndResizing() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TerminalDisplay display = new TerminalDisplay(output, true, 80);
        Screen screen = new Screen(20);
        display.resize(20);
        output.reset();
        display.redraw("> ", "abcdefghijk", 11);
        screen.accept(output.toString(StandardCharsets.UTF_8));
        assertThat(screen.row()).isEqualTo("> abcdefghijk");
        for (int width : new int[]{8, 4, 1, 2, 20}) {
            output.reset();
            screen.resize(width);
            display.resize(width);
            screen.accept(output.toString(StandardCharsets.UTF_8));
            for (int cursor = 0; cursor <= 11; cursor++) {
                output.reset();
                display.redraw("> ", "abcdefghijk", cursor);
                screen.accept(output.toString(StandardCharsets.UTF_8));
                assertThat(screen.cursor).isBetween(0, width - 1);
                assertThat(screen.wrapped).isFalse();
            }
        }
        assertThat(screen.row()).isEqualTo("> abcdefghijk");
        assertThat(screen.cursor).isEqualTo(13);
        output.reset();
        display.redraw("> ", "界e\u0301文", 1);
        screen.accept(output.toString(StandardCharsets.UTF_8));
        assertThat(screen.row()).isEqualTo("> 界e\u0301文");
        assertThat(screen.cursor).isEqualTo(4);
    }

    @Test
    void displaysAnOrphanCombiningMarkWithoutModifyingThePrompt() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TerminalDisplay display = new TerminalDisplay(output, true, 20);
        display.redraw("> ", "\u0301x", 0);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("\r\u001b[2K> ◌\u0301x\u001b[2D");
    }

    private static final class Screen {
        private String[] cells;
        private int cursor;
        private boolean wrapped;

        private Screen(int columns) {
            cells = new String[columns];
            java.util.Arrays.fill(cells, " ");
        }

        private void resize(int columns) {
            int oldLength = cells.length;
            cells = java.util.Arrays.copyOf(cells, columns);
            if (columns > oldLength) {
                java.util.Arrays.fill(cells, oldLength, columns, " ");
            }
            cursor = Math.min(cursor, columns - 1);
        }

        private void accept(String output) {
            for (int offset = 0; offset < output.length();) {
                int point = output.codePointAt(offset);
                offset += Character.charCount(point);
                if (point == '\r') {
                    cursor = 0;
                } else if (point == 0x1B) {
                    assertThat(output.charAt(offset++)).isEqualTo('[');
                    int start = offset;
                    while (Character.isDigit(output.charAt(offset))) {
                        offset++;
                    }
                    int amount = Integer.parseInt(output.substring(start, offset));
                    char command = output.charAt(offset++);
                    if (command == 'K') {
                        assertThat(amount).isEqualTo(2);
                        java.util.Arrays.fill(cells, " ");
                    } else {
                        assertThat(command).isEqualTo('D');
                        cursor = Math.max(0, cursor - amount);
                    }
                } else if (point == 0x301) {
                    cells[cursor - 1] += "\u0301";
                } else {
                    int width = point == '界' || point == '文' ? 2 : 1;
                    if (cursor + width > cells.length) {
                        wrapped = true;
                        cursor = 0;
                    }
                    cells[cursor++] = new String(Character.toChars(point));
                    if (width == 2) {
                        cells[cursor++] = "";
                    }
                }
            }
        }

        private String row() {
            return String.join("", cells).stripTrailing();
        }
    }

    @Test
    void laysOutCandidatesWithinTheTerminalWidth() {
        assertThat(TerminalDisplay.columns(List.of("one", "three", "seven"), 14))
                .isEqualTo("one    three\nseven\n");
        assertThat(TerminalDisplay.columns(List.of("one", "three"), 4))
                .isEqualTo("one\nthree\n");
    }
}
