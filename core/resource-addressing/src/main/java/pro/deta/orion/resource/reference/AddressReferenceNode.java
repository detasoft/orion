package pro.deta.orion.resource.reference;

import java.util.List;

record AddressReferenceNode(ResourceScheme scheme, List<ReferenceNode> body) implements ReferenceNode {
    public AddressReferenceNode {
        if (scheme == null || scheme.isEmpty()) {
            throw new IllegalArgumentException("Address scheme must not be empty");
        }
        body = body == null ? List.of() : List.copyOf(body);
    }
}
