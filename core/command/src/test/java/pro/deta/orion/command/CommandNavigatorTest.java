package pro.deta.orion.command;

import org.junit.jupiter.api.Test;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.command.resource.ScopedResourceCandidate;
import pro.deta.orion.command.resource.ScopedResourceResolver;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CommandNavigatorTest {
    private final CommandContext context = context(CommandPath.root());
    private final CommandNavigator navigator = new CommandNavigator(tree());

    @Test
    void navigatesStaticAndAuthorizedDynamicScopesWithoutLeakingDeniedResources() {
        CommandLocation root = located(navigator.navigate(context, CommandPath.root(), "/"));
        assertThat(root.path()).isEqualTo(CommandPath.root());
        assertThat(root.resources()).isEmpty();

        CommandNavigation organization = navigator.navigate(context, CommandPath.root(), "organization/acme");
        assertThat(organization).isInstanceOf(CommandNavigation.Located.class);
        CommandLocation location = ((CommandNavigation.Located) organization).location();
        assertThat(location.path()).isEqualTo(CommandPath.absolute(List.of("organization", "acme-123")));
        assertThat(location.resources()).containsExactly("acme-value");

        assertThat(navigator.navigate(context, location.path(), ".."))
                .isInstanceOf(CommandNavigation.Located.class)
                .extracting(value -> ((CommandNavigation.Located) value).location().path())
                .isEqualTo(CommandPath.absolute(List.of("organization")));
        assertThat(navigator.navigate(context, CommandPath.root(), ".."))
                .isInstanceOf(CommandNavigation.Missing.class);
        assertThat(navigator.navigate(context, CommandPath.root(), "/organization/hidden"))
                .isInstanceOf(CommandNavigation.Missing.class);
        assertThat(navigator.navigate(context, CommandPath.root(), "/organization/a"))
                .isEqualTo(new CommandNavigation.Ambiguous(List.of("acme-123", "alpha-789")));
    }

    @Test
    void listsOnlyVisibleActionsAndAllowedDynamicIdentifiersAndNames() {
        CommandLocation root = located(navigator.navigate(context, CommandPath.root(), "/"));
        assertThat(navigator.visibleEntries(context, root))
                .containsExactly("organization/", "session/", "whoami");

        CommandNavigation organizationNavigation =
                navigator.navigate(context, CommandPath.root(), "/organization");
        CommandLocation organizations = located(organizationNavigation);
        assertThat(navigator.visibleEntries(context, organizations))
                .containsExactly("acme-123/", "acme/", "alpha-789/", "alpha/");

        CommandLocation session = located(navigator.navigate(context, CommandPath.root(), "/session"));
        assertThat(navigator.visibleEntries(context, session)).containsExactly("ls");
    }

    @Test
    void hidesDynamicNamesWhenTheResolverDisablesNameSelection() {
        ScopedResourceResolver<String> identifiersOnly = new ScopedResourceResolver<>(
                (ignored, parents) -> List.of(candidate("acme-123", "acme", "value", true)),
                false);
        CommandNode root = CommandNode.builder()
                .dynamicChild(identifiersOnly, CommandNode.builder().build())
                .build();
        CommandNavigator identifiersOnlyNavigator = new CommandNavigator(root);
        CommandLocation location = located(identifiersOnlyNavigator.locate(context, CommandPath.root()));

        assertThat(identifiersOnlyNavigator.visibleEntries(context, location)).containsExactly("acme-123/");
        assertThat(identifiersOnlyNavigator.complete(context, CommandPath.root(), "a", 1).candidates())
                .containsExactly("acme-123/");
    }

    @Test
    void completesNamespacesActionsParametersFieldsAndEnums() {
        assertCompletion(CommandPath.root(), "org", 3, "organization/", List.of("organization/"));
        assertCompletion(CommandPath.absolute(List.of("session")), "l", 1, "ls ", List.of("ls"));
        assertCompletion(CommandPath.root(), "/session ls fo", 14, "/session ls format=", List.of("format="));
        assertCompletion(
                CommandPath.root(),
                "/session ls where st",
                20,
                "/session ls where state=",
                List.of("state="));
        assertCompletion(
                CommandPath.root(),
                "/session ls where state=r",
                25,
                "/session ls where state=running ",
                List.of("running"));
    }

    @Test
    void ordersSetBasedParameterAndWhereCompletionsLexically() {
        CommandDefinition inspect = new CommandDefinition(
                "inspect",
                0,
                0,
                Set.of("zeta", "alpha"),
                Set.of(),
                Set.of("zone", "account"),
                ignored -> true,
                ignored -> AccessDecision.allow("test"),
                ignored -> new CommandResult.Message("ok"));
        CommandNavigator ordered = new CommandNavigator(CommandNode.builder().action(inspect).build());

        assertThat(ordered.complete(context, CommandPath.root(), "inspect ", 8).candidates())
                .containsExactly("alpha=", "zeta=", "where");
        assertThat(ordered.complete(context, CommandPath.root(), "inspect where ", 14).candidates())
                .containsExactly("account=", "zone=");
    }

    @Test
    void preservesRegisteredCompletionValueMapOrder() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("zeta", List.of("last"));
        values.put("alpha", List.of("first"));

        CommandCompletion completion = new CommandCompletion(values, Map.of());

        assertThat(completion.namedValues().keySet()).containsExactly("zeta", "alpha");
    }

    @Test
    void preservesAuthorizedAmbiguityAndExtendsOnlyTheSharedPrefix() {
        CommandCompletion.Result result =
                navigator.complete(context, CommandPath.root(), "organization/alp", 16);

        assertThat(result.line()).isEqualTo("organization/alpha");
        assertThat(result.cursor()).isEqualTo(18);
        assertThat(result.candidates()).containsExactly("alpha-789/", "alpha/");
        assertThat(result.candidates()).doesNotContain("hidden/", "amber-456/");
    }

    private void assertCompletion(
            CommandPath path,
            String line,
            int cursor,
            String expectedLine,
            List<String> candidates) {
        CommandCompletion.Result result = navigator.complete(context(path), path, line, cursor);
        assertThat(result.line()).isEqualTo(expectedLine);
        assertThat(result.cursor()).isEqualTo(expectedLine.length());
        assertThat(result.candidates()).containsExactlyElementsOf(candidates);
    }

    private static CommandLocation located(CommandNavigation navigation) {
        return ((CommandNavigation.Located) navigation).location();
    }

    private static CommandContext context(CommandPath path) {
        return new CommandContext(
                SecurityContext.createContext(),
                "request",
                "session",
                "source",
                path,
                CommandPresentation.plain(),
                CommandCancellation.never(),
                Map.of());
    }

    private static CommandNode tree() {
        CommandDefinition list = new CommandDefinition(
                "ls",
                0,
                0,
                Set.of("format"),
                Set.of(),
                Set.of("state"),
                ignored -> true,
                ignored -> AccessDecision.allow("test"),
                ignored -> new CommandResult.Message("ok"),
                new CommandCompletion(
                        Map.of("format", List.of("table", "wide")),
                        Map.of("state", List.of("running", "completed"))));
        CommandDefinition hidden = definition("secret", false);
        CommandNode resource = CommandNode.builder().action(definition("show", true)).build();
        ScopedResourceResolver<String> organizations = new ScopedResourceResolver<>(
                (ignored, parents) -> List.of(
                        candidate("acme-123", "acme", "acme-value", true),
                        candidate("alpha-789", "alpha", "alpha-value", true),
                        candidate("amber-456", "hidden", "hidden-value", false)),
                true);
        CommandNode organization = CommandNode.builder().dynamicChild(organizations, resource).build();
        CommandNode session = CommandNode.builder().action(list).action(hidden).build();
        return CommandNode.builder()
                .child("organization", organization)
                .child("session", session)
                .action(definition("whoami", true))
                .action(definition("shutdown", false))
                .build();
    }

    private static CommandDefinition definition(String action, boolean visible) {
        return new CommandDefinition(
                action,
                0,
                0,
                Set.of(),
                Set.of(),
                Set.of(),
                ignored -> visible,
                ignored -> AccessDecision.allow("test"),
                ignored -> new CommandResult.Message("ok"));
    }

    private static ScopedResourceCandidate<String> candidate(
            String id,
            String name,
            String value,
            boolean allowed) {
        AccessDecision decision = allowed
                ? AccessDecision.allow("test")
                : AccessDecision.deny("test");
        return new ScopedResourceCandidate<>(id, Optional.of(name), value, decision);
    }
}
