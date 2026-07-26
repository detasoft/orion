package pro.deta.orion.git.parser.wire;

import java.util.Objects;
import java.util.Optional;

public record GitCapability(
        String name,
        String rawValue,
        String rawToken) {

    public GitCapability {
        name = validateName(name);
        rawValue = validateValue(rawValue);
        Objects.requireNonNull(rawToken, "rawToken");
    }

    public static GitCapability bare(String name) {
        return new GitCapability(name, null, validateName(name));
    }

    public static GitCapability of(String name, String value) {
        String checkedName = validateName(name);
        String checkedValue = validateValue(Objects.requireNonNull(value, "value"));
        return new GitCapability(checkedName, checkedValue, checkedName + "=" + checkedValue);
    }

    static GitCapability parse(String token) {
        String checkedToken = Objects.requireNonNull(token, "token");
        int valueSeparator = checkedToken.indexOf('=');
        if (valueSeparator < 0) {
            return new GitCapability(checkedToken, null, checkedToken);
        }
        String name = checkedToken.substring(0, valueSeparator);
        String value = checkedToken.substring(valueSeparator + 1);
        return new GitCapability(name, value, checkedToken);
    }

    public Optional<String> value() {
        return Optional.ofNullable(rawValue);
    }

    private static String validateName(String name) {
        String checkedName = Objects.requireNonNull(name, "name");
        if (checkedName.isEmpty()) {
            throw new IllegalArgumentException("Capability name must not be empty");
        }
        // Git wire protocol v2 uses this space-containing header before regular capabilities.
        if ("version 2".equals(checkedName)) {
            return checkedName;
        }
        for (int i = 0; i < checkedName.length(); i++) {
            char ch = checkedName.charAt(i);
            if (Character.isWhitespace(ch) || ch == '=') {
                throw new IllegalArgumentException("Capability name must not contain whitespace or '='");
            }
        }
        return checkedName;
    }

    private static String validateValue(String value) {
        if (value == null) {
            return null;
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Capability value must not contain line endings");
        }
        return value;
    }
}
