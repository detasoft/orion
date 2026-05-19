package pro.deta.orion.resource.reference;

record DocumentReferenceNode(
        DocumentFormat format,
        ReferenceNode source,
        DocumentPath path) implements ReferenceNode {

    public DocumentReferenceNode {
        if (format == null) {
            throw new IllegalArgumentException("Document format must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("Document source must not be null");
        }
        if (path == null) {
            throw new IllegalArgumentException("Document path must not be null");
        }
    }
}
