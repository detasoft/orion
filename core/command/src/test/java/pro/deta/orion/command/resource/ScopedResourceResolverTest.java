package pro.deta.orion.command.resource;

import org.junit.jupiter.api.Test;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.command.CommandCancellation;
import pro.deta.orion.command.CommandContext;
import pro.deta.orion.command.CommandPath;
import pro.deta.orion.command.CommandPresentation;
import pro.deta.orion.auth.check.AccessDecision;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedResourceResolverTest {
    private final CommandContext context = new CommandContext(
            SecurityContext.createContext(),
            "request",
            "session",
            "source",
            CommandPath.root(),
            CommandPresentation.plain(),
            CommandCancellation.never(),
            Map.of());

    @Test
    void exactFullIdWinsOverPrefixAndNameMatches() {
        ScopedResourceResolver<String> resolver = resolver(List.of(
                allowed("abc", "other", "exact"),
                allowed("abcdef", "abc", "prefix-and-name")));

        assertResolved(resolver.resolve(context, List.of(), "abc"), "exact");
    }

    @Test
    void resolvesUniqueAllowedPrefixAndOptionalUniqueName() {
        List<ScopedResourceCandidate<String>> candidates = List.of(
                allowed("abc123", "primary", "one"),
                allowed("def456", "secondary", "two"));

        assertResolved(resolver(candidates).resolve(context, List.of(), "abc"), "one");
        assertResolved(resolver(candidates).resolve(context, List.of(), "secondary"), "two");
        assertThat(resolver(candidates, false).resolve(context, List.of(), "secondary"))
                .isInstanceOf(ScopedResourceResolution.Missing.class);
    }

    @Test
    void ambiguityContainsOnlyAllowedCandidateIdsInCatalogOrder() {
        ScopedResourceResolver<String> resolver = resolver(List.of(
                allowed("abc1", "one", "one"),
                denied("abc2", "two", "two"),
                allowed("abc3", "three", "three")));

        ScopedResourceResolution<String> result = resolver.resolve(context, List.of(), "abc");

        assertThat(result).isEqualTo(new ScopedResourceResolution.Ambiguous<String>(List.of("abc1", "abc3")));
    }

    @Test
    void deniedExactAndAllDeniedMatchesAreIndistinguishableFromMissing() {
        ScopedResourceResolver<String> resolver = resolver(List.of(
                denied("secret", "hidden", "secret-value"),
                allowed("secret-longer", "public", "public-value")));

        assertThat(resolver.resolve(context, List.of(), "secret"))
                .isInstanceOf(ScopedResourceResolution.Missing.class);
        assertThat(resolver.resolve(context, List.of(), "hidden"))
                .isInstanceOf(ScopedResourceResolution.Missing.class);
        assertThat(resolver.resolve(context, List.of(), "absent"))
                .isInstanceOf(ScopedResourceResolution.Missing.class);
    }

    @Test
    void visibleFiltersWithTheSameAccessDecisions() {
        ScopedResourceResolver<String> resolver = resolver(List.of(
                allowed("first", "one", "first-value"),
                denied("second", "two", "second-value"),
                allowed("third", "three", "third-value")));

        assertThat(resolver.visible(context, List.of()))
                .extracting(ScopedResourceCandidate::id)
                .containsExactly("first", "third");
    }

    private static ScopedResourceResolver<String> resolver(List<ScopedResourceCandidate<String>> candidates) {
        return resolver(candidates, true);
    }

    private static ScopedResourceResolver<String> resolver(
            List<ScopedResourceCandidate<String>> candidates,
            boolean namesEnabled) {
        ScopedResourceCatalog<String> catalog = (context, parents) -> candidates;
        return new ScopedResourceResolver<>(catalog, namesEnabled);
    }

    private static ScopedResourceCandidate<String> allowed(String id, String name, String value) {
        return new ScopedResourceCandidate<>(
                id,
                Optional.of(name),
                value,
                AccessDecision.allow("test"));
    }

    private static ScopedResourceCandidate<String> denied(String id, String name, String value) {
        return new ScopedResourceCandidate<>(
                id,
                Optional.of(name),
                value,
                AccessDecision.deny("test"));
    }

    private static void assertResolved(ScopedResourceResolution<String> resolution, String value) {
        assertThat(resolution).isInstanceOf(ScopedResourceResolution.Resolved.class);
        ScopedResourceResolution.Resolved<String> resolved =
                (ScopedResourceResolution.Resolved<String>) resolution;
        assertThat(resolved.candidate().value()).isEqualTo(value);
    }
}
