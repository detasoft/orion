package pro.deta.orion.transport.git.command.read;

import java.util.Objects;
import java.util.Optional;

public final class OperatorDomainViews {
    private OperatorDomainViews() {
    }

    public record RepositoryView(
            String id,
            Optional<String> name,
            String repositoryName,
            String defaultHead,
            int refCount,
            Optional<String> organizationId) {
        public RepositoryView {
            id = pathSegment(id, "id");
            name = optionalPathAlias(name, "name");
            repositoryName = required(repositoryName, "repositoryName");
            defaultHead = required(defaultHead, "defaultHead");
            if (refCount < 0) {
                throw new IllegalArgumentException("refCount must not be negative");
            }
            organizationId = optional(organizationId, "organizationId");
        }
    }

    public record OrganizationView(String id, Optional<String> name) {
        public OrganizationView {
            id = pathSegment(id, "id");
            name = optionalPathAlias(name, "name");
        }
    }

    public record UserView(String id, Optional<String> name, String organizationId, String principalId) {
        public UserView {
            id = pathSegment(id, "id");
            name = optionalPathAlias(name, "name");
            organizationId = required(organizationId, "organizationId");
            principalId = required(principalId, "principalId");
        }
    }

    public record SessionView(
            String id,
            Optional<String> name,
            String state,
            String ownerId,
            Optional<String> repositoryName) {
        public SessionView {
            id = pathSegment(id, "id");
            name = optionalPathAlias(name, "name");
            state = required(state, "state");
            ownerId = required(ownerId, "ownerId");
            repositoryName = optional(repositoryName, "repositoryName");
        }
    }

    public record ProxyView(
            String id,
            Optional<String> name,
            String state,
            Optional<String> repositoryName,
            String remote) {
        public ProxyView {
            id = pathSegment(id, "id");
            name = optionalPathAlias(name, "name");
            state = required(state, "state");
            repositoryName = optional(repositoryName, "repositoryName");
            remote = required(remote, "remote");
        }
    }

    public record SystemResourceView(
            int availableProcessors,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes) implements OperatorQueryResult.ScalarValue {
        public SystemResourceView {
            if (availableProcessors < 1) {
                throw new IllegalArgumentException("availableProcessors must be positive");
            }
            requireNonNegative(heapUsedBytes, "heapUsedBytes");
            requireNonNegative(heapCommittedBytes, "heapCommittedBytes");
            requireNonNegative(heapMaxBytes, "heapMaxBytes");
        }
    }

    public record ServiceView(
            String id,
            String name,
            String state,
            String computedState,
            boolean terminal) {
        public ServiceView {
            id = pathSegment(id, "id");
            name = required(name, "name");
            state = required(state, "state");
            computedState = required(computedState, "computedState");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Optional<String> optional(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        value.ifPresent(item -> required(item, name));
        return value;
    }

    private static Optional<String> optionalPathAlias(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        value.ifPresent(item -> pathSegment(item, name));
        return value;
    }

    private static String pathSegment(String value, String name) {
        required(value, name);
        if (value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException(name + " must not be a navigation operator");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '/'
                    || character == '\''
                    || character == '"'
                    || character == '\\'
                    || Character.isWhitespace(character)
                    || Character.isISOControl(character)) {
                throw new IllegalArgumentException(name + " must be a path-safe command segment");
            }
        }
        return value;
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
