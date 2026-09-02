package pro.deta.orion.git.client;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record GitRemoteAdvertisement(
        Set<String> capabilities,
        List<Ref> refs) {

    public GitRemoteAdvertisement {
        capabilities = Set.copyOf(Objects.requireNonNull(
                capabilities, "capabilities"));
        refs = List.copyOf(Objects.requireNonNull(refs, "refs"));
    }

    public Optional<Ref> findRef(String name) {
        Objects.requireNonNull(name, "name");
        for (Ref ref : refs) {
            if (name.equals(ref.name())) {
                return Optional.of(ref);
            }
        }
        return Optional.empty();
    }

    public record Ref(
            String objectId,
            String name,
            Optional<String> peeledObjectId) {
        public Ref {
            GitClientValidation.requireObjectId(objectId, "objectId");
            GitClientValidation.requireAdvertisedRefName(name, "name");
            peeledObjectId = Objects.requireNonNull(
                    peeledObjectId, "peeledObjectId");
            peeledObjectId.ifPresent(value ->
                    GitClientValidation.requireObjectId(
                            value, "peeledObjectId"));
        }
    }
}
