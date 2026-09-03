package pro.deta.orion.agentd.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class LandlockPolicyCompiler {
    public static final int MAX_GRANTS = 32_768;
    public static final int MAX_DEPTH = 256;
    private static final Comparator<Path> PATH_ORDER = (left, right) -> compareBytes(
            left.toString().getBytes(StandardCharsets.UTF_8),
            right.toString().getBytes(StandardCharsets.UTF_8));

    public CompiledPolicy compile(SourcePolicy source) {
        TrieNode root = buildTrie(source);
        validateSourcePaths(source);
        Map<Path, Long> grants = new TreeMap<>(PATH_ORDER);
        for (LandlockRight right : LandlockRight.values()) {
            compileRight(root, Path.of("/"), right, false, grants, 0);
        }
        List<CompiledPolicy.Rule> rules = new ArrayList<>(grants.size());
        for (Map.Entry<Path, Long> grant : grants.entrySet()) {
            if (grant.getValue() != 0) {
                rules.add(new CompiledPolicy.Rule(grant.getKey(), grant.getValue()));
            }
        }
        return new CompiledPolicy(LandlockRight.HANDLED_MASK, rules);
    }

    private static TrieNode buildTrie(SourcePolicy source) {
        TrieNode root = new TrieNode();
        for (SourcePolicy.Rule rule : source.rules()) {
            if (rule.path().getNameCount() > MAX_DEPTH) {
                throw failure(rule.sourceLine(), rule.path(), "path exceeds traversal depth limit");
            }
            TrieNode node = root;
            for (Path component : rule.path()) {
                node = node.children.computeIfAbsent(component.toString(), ignored -> new TrieNode());
            }
            node.rule = rule;
        }
        return root;
    }

    private static void validateSourcePaths(SourcePolicy source) {
        for (SourcePolicy.Rule rule : source.rules()) {
            Path current = rule.path().getRoot();
            boolean missing = false;
            for (Path component : rule.path()) {
                current = current.resolve(component);
                if (missing) {
                    continue;
                }
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(
                            current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (java.nio.file.NoSuchFileException missingPath) {
                    missing = true;
                    continue;
                } catch (IOException error) {
                    throw failure(rule.sourceLine(), current, "cannot inspect path: " + detail(error));
                }
                if (attributes.isSymbolicLink()) {
                    throw failure(rule.sourceLine(), current, "source path contains a symbolic-link component");
                }
            }
            if (missing && rule.rights() != 0) {
                throw failure(rule.sourceLine(), rule.path(), "missing positive path");
            }
        }
    }

    private static void compileRight(
            TrieNode node,
            Path path,
            LandlockRight right,
            boolean inherited,
            Map<Path, Long> grants,
            int depth
    ) {
        if (depth > MAX_DEPTH) {
            throw failure(node.line(), path, "policy expansion exceeds traversal depth limit");
        }
        boolean allowed = node.rule == null ? inherited : (node.rule.rights() & right.mask()) != 0;
        if (allowed) {
            Restriction restriction = firstRestriction(node, right, allowed, path);
            if (restriction == null) {
                emit(grants, path, right, node.line());
                return;
            }
            if (right.directoryOperation()) {
                throw failure(
                        restriction.line(),
                        restriction.path(),
                        "cannot represent a restrictive descendant for " + right.token());
            }
            splitAllowed(node, path, right, grants, depth);
            return;
        }
        for (Map.Entry<String, TrieNode> child : node.children.entrySet()) {
            if (containsAllowance(child.getValue(), right, false)) {
                compileRight(child.getValue(), path.resolve(child.getKey()), right, false, grants, depth + 1);
            }
        }
    }

    private static void splitAllowed(
            TrieNode node,
            Path path,
            LandlockRight right,
            Map<Path, Long> grants,
            int depth
    ) {
        BasicFileAttributes attributes = attributes(path, node.line());
        if (!attributes.isDirectory()) {
            throw failure(node.line(), path, "restrictive descendant is below a non-directory path");
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
            for (Path entry : entries) {
                BasicFileAttributes childAttributes = Files.readAttributes(
                        entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (childAttributes.isSymbolicLink()) {
                    continue;
                }
                TrieNode policyChild = node.children.get(entry.getFileName().toString());
                if (policyChild == null) {
                    emit(grants, entry, right, node.line());
                } else {
                    compileRight(policyChild, entry, right, true, grants, depth + 1);
                }
            }
        } catch (IOException error) {
            throw failure(node.line(), path, "cannot snapshot directory: " + detail(error));
        }
    }

    private static BasicFileAttributes attributes(Path path, int line) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException error) {
            throw failure(line, path, "cannot inspect positive path: " + detail(error));
        }
    }

    private static Restriction firstRestriction(
            TrieNode node,
            LandlockRight right,
            boolean inherited,
            Path path
    ) {
        for (Map.Entry<String, TrieNode> child : node.children.entrySet()) {
            TrieNode childNode = child.getValue();
            Path childPath = path.resolve(child.getKey());
            boolean childAllowed = childNode.rule == null
                    ? inherited
                    : (childNode.rule.rights() & right.mask()) != 0;
            if (!childAllowed) {
                return new Restriction(childNode.line(), childPath);
            }
            Restriction deeper = firstRestriction(childNode, right, childAllowed, childPath);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    private static boolean containsAllowance(TrieNode node, LandlockRight right, boolean inherited) {
        boolean allowed = node.rule == null ? inherited : (node.rule.rights() & right.mask()) != 0;
        if (allowed) {
            return true;
        }
        for (TrieNode child : node.children.values()) {
            if (containsAllowance(child, right, allowed)) {
                return true;
            }
        }
        return false;
    }

    private static void emit(Map<Path, Long> grants, Path path, LandlockRight right, int line) {
        if (!grants.containsKey(path) && grants.size() == MAX_GRANTS) {
            throw failure(line, path, "compiled policy has too many grants");
        }
        grants.merge(path, right.mask(), (left, value) -> left | value);
    }

    private static int compareBytes(byte[] left, byte[] right) {
        int common = Math.min(left.length, right.length);
        for (int index = 0; index < common; index++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static PolicyException failure(int line, Path path, String detail) {
        return new PolicyException("source line " + line + " at " + path + ": " + detail);
    }

    private static String detail(IOException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static final class TrieNode {
        private final Map<String, TrieNode> children = new HashMap<>();
        private SourcePolicy.Rule rule;

        private int line() {
            return rule == null ? 1 : rule.sourceLine();
        }
    }

    private record Restriction(int line, Path path) {
    }
}
