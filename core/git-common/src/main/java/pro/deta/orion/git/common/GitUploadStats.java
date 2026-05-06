package pro.deta.orion.git.common;

public record GitUploadStats(
        long totalObjects,
        long reusedObjects,
        long packBytes) {
}
