package pro.deta.orion.command.resource;

import pro.deta.orion.command.CommandContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ScopedResourceResolver<T> {
    private final ScopedResourceCatalog<T> catalog;
    private final boolean namesEnabled;

    public ScopedResourceResolver(ScopedResourceCatalog<T> catalog, boolean namesEnabled) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.namesEnabled = namesEnabled;
    }

    public ScopedResourceResolution<T> resolve(
            CommandContext context,
            List<Object> parentResources,
            String selector) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parentResources, "parentResources");
        Objects.requireNonNull(selector, "selector");
        List<ScopedResourceCandidate<T>> candidates = candidates(context, parentResources);

        for (ScopedResourceCandidate<T> candidate : candidates) {
            if (candidate.id().equals(selector)) {
                if (candidate.accessDecision().allowed()) {
                    return new ScopedResourceResolution.Resolved<>(candidate);
                }
                return new ScopedResourceResolution.Missing<>();
            }
        }

        List<ScopedResourceCandidate<T>> prefixMatches = new ArrayList<>();
        for (ScopedResourceCandidate<T> candidate : candidates) {
            if (candidate.accessDecision().allowed() && candidate.id().startsWith(selector)) {
                prefixMatches.add(candidate);
            }
        }
        ScopedResourceResolution<T> prefixResolution = matched(prefixMatches);
        if (!(prefixResolution instanceof ScopedResourceResolution.Missing<T>)) {
            return prefixResolution;
        }

        if (namesEnabled) {
            List<ScopedResourceCandidate<T>> nameMatches = new ArrayList<>();
            for (ScopedResourceCandidate<T> candidate : candidates) {
                if (candidate.accessDecision().allowed()
                        && candidate.name().filter(selector::equals).isPresent()) {
                    nameMatches.add(candidate);
                }
            }
            return matched(nameMatches);
        }
        return new ScopedResourceResolution.Missing<>();
    }

    public List<ScopedResourceCandidate<T>> visible(CommandContext context, List<Object> parentResources) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parentResources, "parentResources");
        List<ScopedResourceCandidate<T>> visible = new ArrayList<>();
        for (ScopedResourceCandidate<T> candidate : candidates(context, parentResources)) {
            if (candidate.accessDecision().allowed()) {
                visible.add(candidate);
            }
        }
        return List.copyOf(visible);
    }

    private List<ScopedResourceCandidate<T>> candidates(
            CommandContext context,
            List<Object> parentResources) {
        return List.copyOf(catalog.candidates(context, List.copyOf(parentResources)));
    }

    private static <T> ScopedResourceResolution<T> matched(List<ScopedResourceCandidate<T>> matches) {
        if (matches.isEmpty()) {
            return new ScopedResourceResolution.Missing<>();
        }
        if (matches.size() == 1) {
            return new ScopedResourceResolution.Resolved<>(matches.getFirst());
        }
        List<String> candidateIds = new ArrayList<>(matches.size());
        for (ScopedResourceCandidate<T> match : matches) {
            candidateIds.add(match.id());
        }
        return new ScopedResourceResolution.Ambiguous<>(candidateIds);
    }
}
