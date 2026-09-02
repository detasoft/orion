package pro.deta.orion.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommandLineParserTest {
    private final CommandLineParser parser = new CommandLineParser();

    @Test
    void parsesAbsoluteAndContextRelativePaths() {
        ParsedCommand absolute = success("/repository/team show", CommandPath.root());
        ParsedCommand oneSegmentRelative = success(
                "repository ls",
                CommandPath.absolute(List.of("organization", "acme")));
        ParsedCommand relative = success(
                "repository/team show",
                CommandPath.absolute(List.of("organization", "acme")));

        assertThat(absolute.path()).isEqualTo(CommandPath.absolute(List.of("repository", "team")));
        assertThat(absolute.action()).isEqualTo("show");
        assertThat(oneSegmentRelative.path()).isEqualTo(CommandPath.absolute(
                List.of("organization", "acme", "repository")));
        assertThat(relative.path()).isEqualTo(CommandPath.absolute(
                List.of("organization", "acme", "repository", "team")));
    }

    @Test
    void parsesShortRootCommandsAndEveryReservedAction() {
        assertThat(success("whoami", CommandPath.root()).action()).isEqualTo("whoami");
        for (String action : List.of("ls", "show", "add", "rm", "attach", "monitor")) {
            assertThat(success(action, CommandPath.root()).action()).isEqualTo(action);
        }
    }

    @Test
    void parsesPositionalNamedAndWhereArguments() {
        ParsedCommand parsed = success(
                "/session ls page=2 owner=ops where state=running owner!=bot",
                CommandPath.root());

        assertThat(parsed.namedParameters()).containsExactly(
                Map.entry("page", "2"),
                Map.entry("owner", "ops"));
        assertThat(parsed.predicates()).containsExactly(
                new WherePredicate("state", WherePredicate.Operator.EQUALS, "running"),
                new WherePredicate("owner", WherePredicate.Operator.NOT_EQUALS, "bot"));

        ParsedCommand positional = success("/auth/key rm SHA256:abc confirm", CommandPath.root());
        assertThat(positional.positionalArguments()).containsExactly("SHA256:abc", "confirm");
    }

    @Test
    void tokenizesQuotesEscapesAndLiteralShellMetacharactersWithoutExecutingThem() {
        ParsedCommand parsed = success(
                "/repository show 'two words' \"double quoted\" escaped\\ value expression='a|b;$(x)>*.txt'",
                CommandPath.root());

        assertThat(parsed.positionalArguments()).containsExactly("two words", "double quoted", "escaped value");
        assertThat(parsed.namedParameters()).containsExactly(Map.entry("expression", "a|b;$(x)>*.txt"));
    }

    @Test
    void normalizesParentSegmentsAndRejectsTraversalAboveRoot() {
        ParsedCommand parsed = success(
                "../repository ls",
                CommandPath.absolute(List.of("organization", "acme")));
        assertThat(parsed.path()).isEqualTo(CommandPath.absolute(List.of("organization", "repository")));

        assertFailure("../../show", CommandPath.absolute(List.of("organization")), "above root");
    }

    @Test
    void returnsStructuredFailuresForInvalidSyntaxAndArguments() {
        assertFailure("/repository show 'unterminated", CommandPath.root(), "unterminated");
        assertFailure("/repository show name=one name=two", CommandPath.root(), "duplicate");
        assertFailure("/session ls where state", CommandPath.root(), "predicate");
        assertFailure("/session ls where state=running broken", CommandPath.root(), "predicate");
        assertFailure("", CommandPath.root(), "empty");
    }

    @Test
    void rejectsTokensBetweenTheSinglePathTokenAndAction() {
        assertFailure("/repository ignored ls", CommandPath.root(), "before action");
        assertFailure("resource/target ignored rm", CommandPath.root(), "before action");
    }

    private ParsedCommand success(String commandLine, CommandPath currentPath) {
        CommandParseResult result = parser.parse(commandLine, currentPath);
        assertThat(result).isInstanceOf(CommandParseResult.Success.class);
        return ((CommandParseResult.Success) result).command();
    }

    private void assertFailure(String commandLine, CommandPath currentPath, String messageFragment) {
        CommandParseResult result = parser.parse(commandLine, currentPath);
        assertThat(result).isInstanceOf(CommandParseResult.Failure.class);
        CommandParseResult.Failure failure = (CommandParseResult.Failure) result;
        assertThat(failure.message()).containsIgnoringCase(messageFragment);
        assertThat(failure.position()).isGreaterThanOrEqualTo(0);
    }
}
