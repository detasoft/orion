package pro.deta.orion.command.resource;

import pro.deta.orion.command.CommandContext;

@FunctionalInterface
public interface ScopedResourceCatalog<T> {
    ScopedResourceCatalogResult<T> candidates(CommandContext context, java.util.List<Object> parentResources);
}
