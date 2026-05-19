package pro.deta.orion.resource.reference;

record LiteralReferenceNode(String value) implements ReferenceNode {
    public LiteralReferenceNode {
        if (value == null) {
            throw new IllegalArgumentException("Literal value must not be null");
        }
    }
}
