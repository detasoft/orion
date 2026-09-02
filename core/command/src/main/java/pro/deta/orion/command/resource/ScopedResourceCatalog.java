package pro.deta.orion.command.resource;

import pro.deta.orion.command.CommandContext;

import java.util.List;

@FunctionalInterface
public interface ScopedResourceCatalog<T> {
    List<ScopedResourceCandidate<T>> candidates(CommandContext context, List<Object> parentResources);
}
