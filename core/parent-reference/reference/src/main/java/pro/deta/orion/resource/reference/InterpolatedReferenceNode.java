package pro.deta.orion.resource.reference;

import java.util.List;

record InterpolatedReferenceNode(List<ReferenceNode> parts) implements ReferenceNode {
    public InterpolatedReferenceNode {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("Interpolation parts must not be empty");
        }
        parts = List.copyOf(parts);
    }
}
