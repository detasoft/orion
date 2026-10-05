package pro.deta.orion.bootstrap.config.location;

import pro.deta.orion.util.ResourceLocation;

import java.util.Optional;

interface ConfigurationLocationReader {
    boolean supports(ResourceLocation location);

    Optional<ConfigurationContent> read(ResourceLocation location);
}
