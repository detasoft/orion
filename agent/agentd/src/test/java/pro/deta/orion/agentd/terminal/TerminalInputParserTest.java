package pro.deta.orion.agentd.terminal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalInputParserTest {
    @Test
    void parsesDetachAcrossInputChunksWithoutForwardingTheEscape() {
        TerminalInputParser parser = new TerminalInputParser();

        TerminalInputParser.Result first = parser.accept("hello\u001d".getBytes(StandardCharsets.UTF_8));
        TerminalInputParser.Result second = parser.accept("dignored".getBytes(StandardCharsets.UTF_8));

        assertThat(first.bytes()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(first.detached()).isFalse();
        assertThat(second.bytes()).isEmpty();
        assertThat(second.detached()).isTrue();
    }

    @Test
    void turnsDoubledEscapeIntoOneLiteralAndPreservesOtherPairs() {
        TerminalInputParser parser = new TerminalInputParser();

        TerminalInputParser.Result result = parser.accept(new byte[]{0x1d, 0x1d, 0x1d, 'x'});

        assertThat(result.bytes()).containsExactly(0x1d, 0x1d, 'x');
        assertThat(result.detached()).isFalse();
    }

    @Test
    void flushesAPendingEscapeAtEndOfInput() {
        TerminalInputParser parser = new TerminalInputParser();

        parser.accept(new byte[]{0x1d});

        assertThat(parser.finish().bytes()).containsExactly(0x1d);
    }
}
