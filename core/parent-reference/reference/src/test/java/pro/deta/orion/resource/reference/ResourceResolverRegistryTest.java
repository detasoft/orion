package pro.deta.orion.resource.reference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceResolverRegistryTest {
    @Test
    void customResolverCanOwnLocalScheme() {
        ResourceReferenceResolver resolver = ResourceReferenceResolver.builder()
                .scope(ResourceReferenceScope.empty())
                .registry(ResourceResolverRegistry.builder()
                        .withDefaults()
                        .add(new LocalRepositoryResolver())
                        .build())
                .build();

        LocalRepository repository = resolver.resolve("local:orion", LocalRepository.class);

        assertThat(repository.name()).isEqualTo("orion");
    }

    @Test
    void failsWhenTwoResolversMatchTheSameCapability() {
        ResourceReferenceResolver resolver = ResourceReferenceResolver.builder()
                .scope(ResourceReferenceScope.empty())
                .registry(ResourceResolverRegistry.builder()
                        .add(new LocalRepositoryResolver())
                        .add(new LocalRepositoryResolver())
                        .build())
                .build();

        assertThatThrownBy(() -> resolver.resolve("local:orion", LocalRepository.class))
                .isInstanceOf(ResourceReferenceResolutionException.class)
                .hasMessageContaining("Ambiguous resource capability resolvers");
    }

    private record LocalRepository(String name) {
    }

    private static final class LocalRepositoryResolver implements ResourceCapabilityResolver<LocalRepository> {
        @Override
        public Class<LocalRepository> targetType() {
            return LocalRepository.class;
        }

        @Override
        public boolean supports(ResourceAddress address, ResourceReferenceScope scope) {
            return address.hasScheme(ResourceScheme.of("local"));
        }

        @Override
        public LocalRepository resolve(ResourceAddress address, ResourceReferenceScope scope) {
            return new LocalRepository(address.body());
        }
    }
}
