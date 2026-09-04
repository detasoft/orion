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
        ScopedResourceCatalogResult<T> catalogResult = candidates(context, parentResources);
        if (catalogResult instanceof ScopedResourceCatalogResult.Unavailable<T> unavailable) {
            return new ScopedResourceResolution.Unavailable<>(unavailable.source());
        }
        if (catalogResult instanceof ScopedResourceCatalogResult.AccessDenied<T> denied) {
            return new ScopedResourceResolution.AccessDenied<>(denied.reason());
        }
        if (catalogResult instanceof ScopedResourceCatalogResult.Failed<T> failed) {
            return new ScopedResourceResolution.Failed<>(failed.source(), failed.throwable());
        }
        List<ScopedResourceCandidate<T>> candidates =
                ((ScopedResourceCatalogResult.Available<T>) catalogResult).candidates();
        List<ScopedResourceCandidate<T>> allowed = new ArrayList<>();
        for (ScopedResourceCandidate<T> candidate : candidates) {
            if (candidate.accessDecision().allowed()) {
                allowed.add(candidate);
            }
        }

        for (ScopedResourceCandidate<T> candidate : allowed) {
            if (candidate.id().equals(selector)) {
                return new ScopedResourceResolution.Resolved<>(candidate);
            }
        }

        List<ScopedResourceCandidate<T>> prefixMatches = new ArrayList<>();
        for (ScopedResourceCandidate<T> candidate : allowed) {
            if (candidate.id().startsWith(selector)) {
                prefixMatches.add(candidate);
            }
        }
        ScopedResourceResolution<T> prefixResolution = matched(prefixMatches);
        if (!(prefixResolution instanceof ScopedResourceResolution.Missing<T>)) {
            return prefixResolution;
        }

        if (namesEnabled) {
            List<ScopedResourceCandidate<T>> nameMatches = new ArrayList<>();
            for (ScopedResourceCandidate<T> candidate : allowed) {
                if (candidate.name().filter(selector::equals).isPresent()) {
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
        ScopedResourceCatalogResult<T> catalogResult = candidates(context, parentResources);
        if (!(catalogResult instanceof ScopedResourceCatalogResult.Available<T> available)) {
            return List.of();
        }
        List<ScopedResourceCandidate<T>> visible = new ArrayList<>();
        for (ScopedResourceCandidate<T> candidate : available.candidates()) {
            if (candidate.accessDecision().allowed()) {
                visible.add(candidate);
            }
        }
        return List.copyOf(visible);
    }

    public boolean namesEnabled() {
        return namesEnabled;
    }

    private ScopedResourceCatalogResult<T> candidates(
            CommandContext context,
            List<Object> parentResources) {
        return Objects.requireNonNull(
                catalog.candidates(context, List.copyOf(parentResources)),
                "catalog result");
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
