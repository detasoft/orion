package pro.deta.orion.resource.reference;

sealed interface ReferenceNode permits
        LiteralReferenceNode,
        VariableReferenceNode,
        InterpolatedReferenceNode,
        AddressReferenceNode,
        DocumentReferenceNode {
}
