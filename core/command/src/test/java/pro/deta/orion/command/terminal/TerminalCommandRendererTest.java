package pro.deta.orion.command.terminal;

import org.junit.jupiter.api.Test;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalCommandRendererTest {
    private final TerminalCommandRenderer renderer = new TerminalCommandRenderer();

    @Test
    void rendersRowsForTheAvailableWidth() {
        CommandResult.Rows rows = new CommandResult.Rows(
                List.of("NAME", "STATE"),
                List.of(List.of("first", "running"), List.of("second", "completed")));

        assertThat(renderer.render(rows, 24).stdout())
                .isEqualTo("NAME    STATE\nfirst   running\nsecond  completed\n");
        assertThat(renderer.render(rows, 9).stdout())
                .isEqualTo("NAME\tSTATE\nfirst\trunning\nsecond\tcompleted\n");
    }

    @Test
    void retainsStablePlainExitAndFailureBehavior() {
        assertThat(renderer.render(new CommandResult.Exit(7, "stopped"), 80).exitCode()).isEqualTo(7);
        assertThat(renderer.render(
                new CommandResult.Failure(CommandFailureCode.ACCESS_DENIED, "Access denied", List.of()),
                80).stderr())
                .isEqualTo("ACCESS_DENIED: Access denied\n");
    }

    @Test
    void fallsBackToPlainRenderingForWiderAndShorterRaggedRows() {
        CommandResult.Rows wider = new CommandResult.Rows(
                List.of("NAME"),
                List.of(List.of("first", "running")));
        CommandResult.Rows shorter = new CommandResult.Rows(
                List.of("NAME", "STATE"),
                List.of(List.of("first")));

        assertThat(renderer.render(wider, 80).stdout()).isEqualTo("NAME\nfirst\trunning\n");
        assertThat(renderer.render(shorter, 80).stdout()).isEqualTo("NAME\tSTATE\nfirst\n");
    }

    @Test
    void measuresAndRendersEscapedStructuredValues() {
        CommandResult.Rows rows = new CommandResult.Rows(
                List.of("NAME", "STATE"),
                List.of(List.of("a\nb", "x\u001b\\y")));

        assertThat(renderer.render(rows, 16).stdout())
                .isEqualTo("NAME  STATE\na\\nb  x\\u001B\\\\y\n");
        assertThat(renderer.render(rows, 15).stdout())
                .isEqualTo("NAME\tSTATE\na\\nb\tx\\u001B\\\\y\n");
    }
}
