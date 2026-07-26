package pro.deta.orion.git.parser.wire;

import java.util.Objects;
import java.util.Optional;

public record GitReportStatusRef(
        Status status,
        String refName,
        Optional<String> reason) {

    public GitReportStatusRef {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(refName, "refName");
        Objects.requireNonNull(reason, "reason");
        validateRefName(refName);
        if (status == Status.OK && reason.isPresent()) {
            throw new IllegalArgumentException("Accepted ref status must not include a reason");
        }
        if (status == Status.NG && reason.isEmpty()) {
            throw new IllegalArgumentException("Rejected ref status must include a reason");
        }
        reason.ifPresent(GitReportStatusRef::validateReason);
    }

    public static GitReportStatusRef ok(String refName) {
        return new GitReportStatusRef(Status.OK, refName, Optional.empty());
    }

    public static GitReportStatusRef ng(String refName, String reason) {
        return new GitReportStatusRef(Status.NG, refName, Optional.of(reason));
    }

    private static void validateRefName(String refName) {
        if (refName.isBlank()) {
            throw new IllegalArgumentException("Ref name must not be blank");
        }
        if (refName.indexOf('\n') >= 0 || refName.indexOf('\r') >= 0 || containsWhitespace(refName)) {
            throw new IllegalArgumentException("Ref name must not contain whitespace or line endings");
        }
    }

    private static void validateReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("Rejected ref status must include a reason");
        }
        if (reason.indexOf('\n') >= 0 || reason.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Rejected ref reason must not contain line endings");
        }
    }

    private static boolean containsWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    public enum Status {
        OK,
        NG
    }
}
