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
        TerminalDisplay ansi = new TerminalDisplay(ansiOutput, true);
        ansi.redraw("prompt> ", "abcd", 2);
        assertThat(ansiOutput.toString(StandardCharsets.UTF_8))
                .isEqualTo("\r\u001b[2Kprompt> abcd\u001b[2D");

        ByteArrayOutputStream plainOutput = new ByteArrayOutputStream();
        TerminalDisplay plain = new TerminalDisplay(plainOutput, false);
        plain.redraw("prompt> ", "abcd", 2);
        assertThat(plainOutput.toString(StandardCharsets.UTF_8))
                .isEqualTo("\rprompt> abcd\b\b");
        assertThat(plainOutput.toString(StandardCharsets.UTF_8)).doesNotContain("\u001b[");
    }

    @Test
    void plainRedrawErasesCharactersFromThePreviousLongerFrame() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TerminalDisplay display = new TerminalDisplay(output, false);

        display.redraw("prompt> ", "abc", 3);
        output.reset();
        display.redraw("prompt> ", "ab", 2);

        assertThat(output.toString(StandardCharsets.UTF_8))
                .isEqualTo("\r" + " ".repeat(11) + "\rprompt> ab");
    }

    @Test
    void laysOutCandidatesWithinTheTerminalWidth() {
        assertThat(TerminalDisplay.columns(List.of("one", "three", "seven"), 14))
                .isEqualTo("one    three\nseven\n");
        assertThat(TerminalDisplay.columns(List.of("one", "three"), 4))
                .isEqualTo("one\nthree\n");
    }
}
