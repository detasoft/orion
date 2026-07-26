package pro.deta.orion.git.parser.wire.reportstatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record GitReportStatus(
        boolean unpackOk,
        Optional<String> unpackError,
        List<GitReportStatusRef> refs) {

    public GitReportStatus {
        Objects.requireNonNull(unpackError, "unpackError");
        Objects.requireNonNull(refs, "refs");
        if (unpackOk && unpackError.isPresent()) {
            throw new IllegalArgumentException("Successful unpack status must not include an error");
        }
        if (!unpackOk && unpackError.isEmpty()) {
            throw new IllegalArgumentException("Failed unpack status must include an error");
        }
        unpackError.ifPresent(GitReportStatus::validateReason);
        refs = List.copyOf(refs);
    }

    public static GitReportStatus unpackOk(List<GitReportStatusRef> refs) {
        return new GitReportStatus(true, Optional.empty(), refs);
    }

    public static GitReportStatus unpackError(String reason, List<GitReportStatusRef> refs) {
        return new GitReportStatus(false, Optional.of(reason), refs);
    }

    private static void validateReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("Unpack error reason must not be blank");
        }
        if (reason.indexOf('\n') >= 0 || reason.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Unpack error reason must not contain line endings");
        }
    }
}
