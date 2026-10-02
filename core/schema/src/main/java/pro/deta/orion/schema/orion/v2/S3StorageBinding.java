package pro.deta.orion.schema.orion.v2;

import java.net.URI;

/** Repository metadata location; transport and credentials belong to the referenced connection. */
public record S3StorageBinding(ConnectionReference connection, URI location) {
    public S3StorageBinding {
        java.util.Objects.requireNonNull(connection, "storage connection");
        java.util.Objects.requireNonNull(location, "storage location");
        String bucket = location.getHost();
        if (!"s3".equals(location.getScheme()) || bucket == null
                || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || bucket.contains("..")
                || location.getUserInfo() != null || location.getPort() != -1
                || location.getQuery() != null || location.getFragment() != null) {
            throw new IllegalArgumentException("S3 storage location must be s3://bucket/prefix");
        }
        String path = location.getPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            String prefix = path.substring(1);
            if (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
            for (String segment : prefix.split("/", -1)) {
                if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                        || segment.contains("\\") || segment.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("Invalid S3 storage prefix");
                }
            }
        }
    }
}
