package pro.deta.orion.keymaterial;

public sealed interface KeyMaterialScope permits KeyMaterialScope.Cluster, KeyMaterialScope.Node {
    String canonicalName();

    static KeyMaterialScope cluster(String clusterId) {
        return new Cluster(clusterId);
    }

    static KeyMaterialScope node(String clusterId, String nodeId) {
        return new Node(clusterId, nodeId);
    }

    record Cluster(String clusterId) implements KeyMaterialScope {
        public Cluster {
            requireIdentifier(clusterId, "Cluster identifier");
        }

        @Override
        public String canonicalName() {
            return "cluster:" + clusterId.length() + ":" + clusterId;
        }
    }

    record Node(String clusterId, String nodeId) implements KeyMaterialScope {
        public Node {
            requireIdentifier(clusterId, "Cluster identifier");
            requireIdentifier(nodeId, "Node identifier");
        }

        @Override
        public String canonicalName() {
            return "node:" + clusterId.length() + ":" + clusterId
                    + ":" + nodeId.length() + ":" + nodeId;
        }
    }

    private static void requireIdentifier(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
    }
}
