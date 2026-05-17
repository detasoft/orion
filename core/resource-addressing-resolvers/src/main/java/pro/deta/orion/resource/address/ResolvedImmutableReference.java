package pro.deta.orion.resource.address;

import java.util.Optional;

public final class ResolvedImmutableReference implements ImmutableReference {
    private final ResourceContent content;
    private final String version;

    public ResolvedImmutableReference(ResourceContent content) {
        if (content == null) {
            throw new IllegalArgumentException("Immutable reference content must not be null");
        }
        this.content = content;
        this.version = ResourceContentVersions.sha256(content.bytes());
    }

    @Override
    public Optional<ResourceContent> read() {
        return Optional.of(new ResourceContent(content.sourceName(), content.bytes(), version));
    }
}
