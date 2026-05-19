package pro.deta.orion.resource.reference;

public record ResourceResolution<T>(
        ResourceReference original,
        T value,
        String safeDisplayName) {
}
