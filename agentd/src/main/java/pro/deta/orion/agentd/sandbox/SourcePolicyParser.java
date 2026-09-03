package pro.deta.orion.agentd.sandbox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SourcePolicyParser {
    public static final int MAX_SOURCE_BYTES = 1024 * 1024;
    public static final int MAX_RULES = 4096;
    public static final int MAX_PATH_BYTES = 4096;

    public SourcePolicy parse(String source) {
        if (source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw error(1, 1, "policy is too large");
        }
        String[] lines = source.split("\\R", -1);
        Map<Path, SourcePolicy.Rule> rules = new LinkedHashMap<>();
        boolean header = false;
        for (int index = 0; index < lines.length; index++) {
            Scanner scanner = new Scanner(lines[index], index + 1);
            scanner.space();
            if (scanner.endOrComment()) {
                continue;
            }
            if (!header) {
                String keyword = scanner.token();
                scanner.requiredSpace();
                String version = scanner.token();
                scanner.finish();
                if (!keyword.equals("landlock") || !version.equals("1")) {
                    throw error(index + 1, 1, "expected 'landlock 1' header");
                }
                header = true;
                continue;
            }
            long rights = scanner.permissions();
            scanner.requiredSpace();
            Path path = validatePath(scanner.quotedPath(), index + 1);
            scanner.finish();
            if (!rules.containsKey(path) && rules.size() == MAX_RULES) {
                throw error(index + 1, 1, "policy has too many rules");
            }
            rules.remove(path);
            rules.put(path, new SourcePolicy.Rule(path, rights, index + 1));
        }
        if (!header) {
            throw error(1, 1, "missing 'landlock 1' header");
        }
        return new SourcePolicy(List.copyOf(rules.values()));
    }

    private static Path validatePath(String text, int line) {
        if (text.indexOf('\0') >= 0 || text.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES) {
            throw error(line, 1, "path is invalid or too long");
        }
        Path path;
        try {
            path = Path.of(text);
        } catch (RuntimeException invalid) {
            throw new PolicyException("line " + line + ", column 1: invalid path", invalid);
        }
        if (!path.isAbsolute() || !path.normalize().equals(path) || hasDotComponent(text)) {
            throw error(line, 1, "path must be absolute and lexically normalized");
        }
        return path;
    }

    private static boolean hasDotComponent(String path) {
        for (String component : path.split("/", -1)) {
            if (component.equals(".") || component.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static PolicyException error(int line, int column, String detail) {
        return new PolicyException("line " + line + ", column " + column + ": " + detail);
    }

    private static final class Scanner {
        private final String line;
        private final int lineNumber;
        private int offset;

        private Scanner(String line, int lineNumber) {
            this.line = line;
            this.lineNumber = lineNumber;
        }

        private long permissions() {
            if (peek('[')) {
                offset++;
                space();
                Set<String> tokens = new HashSet<>();
                long rights = 0;
                if (peek(']')) {
                    fail("permission list is empty");
                }
                while (true) {
                    String token = token();
                    if (token.equals("none")) {
                        fail("none is valid only by itself");
                    }
                    if (!tokens.add(token)) {
                        fail("duplicate permission '" + token + "'");
                    }
                    rights |= namedPermissions(token);
                    space();
                    if (peek(']')) {
                        offset++;
                        return rights;
                    }
                    if (!peek(',')) {
                        fail("expected ',' or ']'");
                    }
                    offset++;
                    space();
                }
            }
            String preset = token();
            return namedPermissions(preset);
        }

        private long namedPermissions(String preset) {
            return switch (preset) {
                case "none" -> 0;
                case "ro" -> LandlockRight.READ_FILE.mask();
                case "rw" -> LandlockRight.READ_FILE.mask() | LandlockRight.WRITE_FILE.mask()
                        | LandlockRight.TRUNCATE.mask();
                case "rox" -> LandlockRight.READ_FILE.mask() | LandlockRight.EXECUTE.mask();
                case "rwx" -> LandlockRight.READ_FILE.mask() | LandlockRight.WRITE_FILE.mask()
                        | LandlockRight.TRUNCATE.mask() | LandlockRight.EXECUTE.mask();
                default -> permission(preset);
            };
        }

        private long permission(String token) {
            LandlockRight right = LandlockRight.forToken(token);
            if (right == null) {
                fail("unknown permission '" + token + "'");
            }
            return right.mask();
        }

        private String quotedPath() {
            if (!peek('"')) {
                fail("expected quoted path");
            }
            offset++;
            StringBuilder value = new StringBuilder();
            while (offset < line.length()) {
                char character = line.charAt(offset++);
                if (character == '"') {
                    return value.toString();
                }
                if (character == '\\') {
                    if (offset == line.length()) {
                        fail("unterminated escape");
                    }
                    char escaped = line.charAt(offset++);
                    if (escaped != '\\' && escaped != '"') {
                        fail("unsupported path escape");
                    }
                    value.append(escaped);
                } else {
                    value.append(character);
                }
            }
            fail("unterminated quoted path");
            return "";
        }

        private String token() {
            int start = offset;
            while (offset < line.length()) {
                char character = line.charAt(offset);
                if (Character.isWhitespace(character) || character == ',' || character == ']') {
                    break;
                }
                offset++;
            }
            if (start == offset) {
                fail("expected token");
            }
            return line.substring(start, offset);
        }

        private void requiredSpace() {
            if (offset == line.length() || !Character.isWhitespace(line.charAt(offset))) {
                fail("expected whitespace");
            }
            space();
        }

        private void space() {
            while (offset < line.length() && Character.isWhitespace(line.charAt(offset))) {
                offset++;
            }
        }

        private boolean endOrComment() {
            return offset == line.length() || line.charAt(offset) == '#';
        }

        private void finish() {
            space();
            if (!endOrComment()) {
                fail("unexpected trailing input");
            }
        }

        private boolean peek(char expected) {
            return offset < line.length() && line.charAt(offset) == expected;
        }

        private void fail(String detail) {
            throw error(lineNumber, offset + 1, detail);
        }
    }
}
