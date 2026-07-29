package pro.deta.orion.git.parser.wire.capability;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class GitCapability {
    public static final GitCapability MULTI_ACK = bare("multi_ack");
    public static final GitCapability MULTI_ACK_DETAILED =
            bare("multi_ack_detailed");
    public static final GitCapability NO_DONE = bare("no-done");
    public static final GitCapability THIN_PACK = bare("thin-pack");
    public static final GitCapability NO_THIN = bare("no-thin");
    public static final GitCapability SIDE_BAND = bare("side-band");
    public static final GitCapability SIDE_BAND_64K = bare("side-band-64k");
    public static final GitCapability OFS_DELTA = bare("ofs-delta");
    public static final GitCapability SHALLOW = bare("shallow");
    public static final GitCapability DEEPEN_SINCE = bare("deepen-since");
    public static final GitCapability DEEPEN_NOT = bare("deepen-not");
    public static final GitCapability DEEPEN_RELATIVE =
            bare("deepen-relative");
    public static final GitCapability NO_PROGRESS = bare("no-progress");
    public static final GitCapability INCLUDE_TAG = bare("include-tag");
    public static final GitCapability REPORT_STATUS = bare("report-status");
    public static final GitCapability REPORT_STATUS_V2 =
            bare("report-status-v2");
    public static final GitCapability DELETE_REFS = bare("delete-refs");
    public static final GitCapability QUIET = bare("quiet");
    public static final GitCapability ATOMIC = bare("atomic");
    public static final GitCapability PUSH_OPTIONS = bare("push-options");
    public static final GitCapability ALLOW_TIP_SHA1_IN_WANT =
            bare("allow-tip-sha1-in-want");
    public static final GitCapability ALLOW_REACHABLE_SHA1_IN_WANT =
            bare("allow-reachable-sha1-in-want");
    public static final GitCapability FILTER = bare("filter");

    private static final Set<String> STANDARD_NAMES = Set.of(
            "multi_ack",
            "multi_ack_detailed",
            "no-done",
            "thin-pack",
            "no-thin",
            "side-band",
            "side-band-64k",
            "ofs-delta",
            "agent",
            "object-format",
            "symref",
            "shallow",
            "deepen-since",
            "deepen-not",
            "deepen-relative",
            "no-progress",
            "include-tag",
            "report-status",
            "report-status-v2",
            "delete-refs",
            "quiet",
            "atomic",
            "push-options",
            "allow-tip-sha1-in-want",
            "allow-reachable-sha1-in-want",
            "push-cert",
            "filter",
            "session-id");

    private final String name;
    private final Optional<String> value;

    private GitCapability(
            String name,
            Optional<String> value) {
        this.name = validateName(name);
        this.value = Objects.requireNonNull(value, "value")
                .map(GitCapability::validateValue);
    }

    public static GitCapability agent(String value) {
        return valued("agent", value);
    }

    public static GitCapability objectFormat(String value) {
        return valued("object-format", value);
    }

    public static GitCapability symref(
            String source,
            String target) {
        return valued(
                "symref",
                validateValue(source) + ":" + validateValue(target));
    }

    public static GitCapability pushCert(String nonce) {
        return valued("push-cert", nonce);
    }

    public static GitCapability sessionId(String value) {
        return valued("session-id", value);
    }

    public static GitCapability custom(String name) {
        return custom(name, Optional.empty());
    }

    public static GitCapability custom(
            String name,
            String value) {
        return custom(
                name,
                Optional.of(Objects.requireNonNull(value, "value")));
    }

    public String name() {
        return name;
    }

    public Optional<String> value() {
        return value;
    }

    public String wireToken() {
        return value.map(item -> name + "=" + item).orElse(name);
    }

    private static GitCapability custom(
            String name,
            Optional<String> value) {
        String checkedName = validateName(name);
        if (STANDARD_NAMES.contains(checkedName)) {
            throw new IllegalArgumentException(
                    "Standard capability must use its typed instance or factory");
        }
        return new GitCapability(checkedName, value);
    }

    private static GitCapability bare(String name) {
        return new GitCapability(name, Optional.empty());
    }

    private static GitCapability valued(
            String name,
            String value) {
        return new GitCapability(
                name,
                Optional.of(Objects.requireNonNull(value, "value")));
    }

    private static String validateName(String name) {
        String checked = Objects.requireNonNull(name, "name");
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(
                    "Capability name must not be empty");
        }
        for (int index = 0; index < checked.length(); index++) {
            char value = checked.charAt(index);
            if (Character.isWhitespace(value) || value == '=') {
                throw new IllegalArgumentException(
                        "Capability name must not contain whitespace or '='");
            }
        }
        return checked;
    }

    private static String validateValue(String value) {
        String checked = Objects.requireNonNull(value, "value");
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(
                    "Capability value must not be empty");
        }
        for (int index = 0; index < checked.length(); index++) {
            char character = checked.charAt(index);
            if (character <= 32 || character >= 127) {
                throw new IllegalArgumentException(
                        "Capability value must contain printable non-space ASCII");
            }
        }
        return checked;
    }
}
