package pro.deta.orion.command;

import pro.deta.orion.command.resource.ScopedResourceCandidate;
import pro.deta.orion.command.resource.ScopedResourceResolution;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CommandNavigator {
    private final CommandNode root;

    public CommandNavigator(CommandNode root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    public CommandNavigation locate(CommandContext context, CommandPath path) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(path, "path");
        if (!path.absolute()) {
            throw new IllegalArgumentException("path must be absolute");
        }
        return locateSegments(context, path.segments());
    }

    public CommandNavigation navigate(CommandContext context, CommandPath currentPath, String expression) {
        Objects.requireNonNull(currentPath, "currentPath");
        Objects.requireNonNull(expression, "expression");
        if (!currentPath.absolute()) {
            throw new IllegalArgumentException("current path must be absolute");
        }
        List<String> segments = normalized(currentPath, expression);
        if (segments == null) {
            return new CommandNavigation.Missing();
        }
        return locateSegments(context, segments);
    }

    public List<String> visibleEntries(CommandContext context, CommandLocation location) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(location, "location");
        List<String> entries = new ArrayList<>();
        for (String child : location.node().children().keySet()) {
            entries.add(child + "/");
        }
        CommandNode.DynamicChild dynamic = location.node().dynamicChild();
        if (dynamic != null) {
            List<? extends ScopedResourceCandidate<?>> candidates =
                    dynamic.resolver().visible(context, location.resources());
            for (ScopedResourceCandidate<?> candidate : candidates) {
                entries.add(candidate.id() + "/");
                if (dynamic.resolver().namesEnabled()) {
                    candidate.name().ifPresent(name -> entries.add(name + "/"));
                }
            }
        }
        for (CommandDefinition definition : location.node().actions().values()) {
            if (definition.visibility().test(context)) {
                entries.add(definition.action());
            }
        }
        return List.copyOf(entries);
    }

    public CommandCompletion.Result complete(
            CommandContext context,
            CommandPath currentPath,
            String line,
            int cursor) {
        Objects.requireNonNull(line, "line");
        if (cursor < 0 || cursor > line.length()) {
            throw new IllegalArgumentException("cursor is outside line");
        }
        String prefix = line.substring(0, cursor);
        int tokenStart = tokenStart(prefix);
        String token = prefix.substring(tokenStart);
        List<String> candidates = argumentCandidates(context, currentPath, prefix, tokenStart, token);
        boolean pathCandidates = false;
        if (candidates == null) {
            candidates = pathAndActionCandidates(context, currentPath, token);
            pathCandidates = true;
        }
        List<String> matches = matching(candidates, tokenFragment(token));
        if (matches.isEmpty()) {
            return new CommandCompletion.Result(line, cursor, List.of());
        }
        String shared = commonPrefix(matches);
        String replacement = replacementToken(token, shared, pathCandidates);
        boolean unique = matches.size() == 1;
        String suffix = unique ? completionSuffix(shared) : "";
        String completedPrefix = prefix.substring(0, tokenStart) + replacement + suffix;
        String completed = completedPrefix + line.substring(cursor);
        return new CommandCompletion.Result(completed, completedPrefix.length(), matches);
    }

    private CommandNavigation locateSegments(CommandContext context, List<String> requestedSegments) {
        CommandNode node = root;
        List<Object> resources = new ArrayList<>();
        List<String> canonical = new ArrayList<>();
        for (String segment : requestedSegments) {
            CommandNode staticChild = node.children().get(segment);
            if (staticChild != null) {
                canonical.add(segment);
                node = staticChild;
                continue;
            }
            CommandNode.DynamicChild dynamic = node.dynamicChild();
            if (dynamic == null) {
                return new CommandNavigation.UnknownPath();
            }
            ScopedResourceResolution<?> resolution = dynamic.resolver().resolve(context, resources, segment);
            if (resolution instanceof ScopedResourceResolution.Missing<?>) {
                return new CommandNavigation.Missing();
            }
            if (resolution instanceof ScopedResourceResolution.Ambiguous<?> ambiguous) {
                return new CommandNavigation.Ambiguous(ambiguous.candidateIds());
            }
            if (resolution instanceof ScopedResourceResolution.Unavailable<?> unavailable) {
                return new CommandNavigation.Unavailable(unavailable.source());
            }
            if (resolution instanceof ScopedResourceResolution.AccessDenied<?> denied) {
                return new CommandNavigation.AccessDenied(denied.reason());
            }
            if (resolution instanceof ScopedResourceResolution.Failed<?> failed) {
                return new CommandNavigation.Failed(failed.source(), failed.throwable());
            }
            ScopedResourceResolution.Resolved<?> resolved = (ScopedResourceResolution.Resolved<?>) resolution;
            canonical.add(resolved.candidate().id());
            resources.add(resolved.candidate().value());
            node = dynamic.node();
        }
        CommandPath path = CommandPath.absolute(canonical);
        return new CommandNavigation.Located(new CommandLocation(path, node, resources));
    }

    private List<String> argumentCandidates(
            CommandContext context,
            CommandPath currentPath,
            String prefix,
            int tokenStart,
            String token) {
        String before = prefix.substring(0, tokenStart).trim();
        if (before.isEmpty()) {
            return null;
        }
        String[] tokens = before.split("\\s+");
        CommandLocation location;
        String action;
        int argumentStart;
        CommandNavigation firstAsPath = navigate(context, currentPath, tokens[0]);
        if (firstAsPath instanceof CommandNavigation.Located located && tokens.length >= 2) {
            location = located.location();
            action = tokens[1];
            argumentStart = 2;
        } else {
            CommandNavigation current = locate(context, currentPath);
            if (!(current instanceof CommandNavigation.Located located)) {
                return List.of();
            }
            location = located.location();
            action = tokens[0];
            argumentStart = 1;
        }
        CommandDefinition definition = findVisibleAction(context, location.node(), action);
        if (definition == null || tokens.length < argumentStart) {
            return null;
        }
        if (contains(tokens, argumentStart, "where")) {
            int operator = token.indexOf("!=");
            if (operator < 0) {
                operator = token.indexOf('=');
            }
            if (operator >= 0) {
                String field = token.substring(0, operator);
                return definition.query().knownValues().getOrDefault(field, List.of());
            }
            List<String> fields = new ArrayList<>();
            for (String field : definition.query().fields()) {
                fields.add(field + "=");
            }
            return fields;
        }
        int equals = token.indexOf('=');
        if (equals >= 0) {
            String name = token.substring(0, equals);
            if (definition.query().enabled() && name.equals("format")) {
                return List.of("json", "plain", "table", "terse");
            }
            if (definition.query().enabled() && name.equals("columns")) {
                return definition.query().fields();
            }
            return definition.completion().namedValues().getOrDefault(name, List.of());
        }
        List<String> names = new ArrayList<>();
        List<String> orderedNames = new ArrayList<>(definition.allowedNamedParameters());
        if (definition.query().enabled()) {
            orderedNames.addAll(CommandQuery.NAMED_PARAMETERS);
        }
        orderedNames.sort(String::compareTo);
        for (String name : orderedNames) {
            names.add(name + "=");
        }
        if (definition.query().enabled()
                && (token.equals("where") || "where".startsWith(token))) {
            names.add("where");
        }
        return names;
    }

    private List<String> pathAndActionCandidates(
            CommandContext context,
            CommandPath currentPath,
            String token) {
        int slash = token.lastIndexOf('/');
        String parentExpression = slash < 0 ? "" : token.substring(0, slash + 1);
        String fragment = slash < 0 ? token : token.substring(slash + 1);
        CommandNavigation navigation = parentExpression.isEmpty()
                ? locate(context, currentPath)
                : navigate(context, currentPath, parentExpression);
        if (!(navigation instanceof CommandNavigation.Located located)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String entry : visibleEntries(context, located.location())) {
            if (entry.startsWith(fragment)) {
                values.add(entry);
            }
        }
        return values;
    }

    private static CommandDefinition findVisibleAction(
            CommandContext context,
            CommandNode node,
            String action) {
        for (Map.Entry<String, CommandDefinition> entry : node.actions().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(action) && entry.getValue().visibility().test(context)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static List<String> normalized(CommandPath currentPath, String expression) {
        List<String> segments = new ArrayList<>();
        if (!expression.startsWith("/")) {
            segments.addAll(currentPath.segments());
        }
        for (String segment : expression.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    return null;
                }
                segments.removeLast();
            } else {
                segments.add(segment);
            }
        }
        return segments;
    }

    private static boolean contains(String[] values, int start, String expected) {
        for (int index = start; index < values.length; index++) {
            if (values[index].equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static int tokenStart(String value) {
        int index = value.length();
        while (index > 0 && !Character.isWhitespace(value.charAt(index - 1))) {
            index--;
        }
        return index;
    }

    private static String tokenFragment(String token) {
        int equals = token.indexOf('=');
        if (equals >= 0) {
            if (token.startsWith("columns=")) {
                int comma = token.lastIndexOf(',');
                return token.substring(Math.max(equals, comma) + 1);
            }
            return token.substring(equals + 1);
        }
        int slash = token.lastIndexOf('/');
        return slash < 0 ? token : token.substring(slash + 1);
    }

    private static String replacementToken(String token, String shared, boolean pathCandidate) {
        int equals = token.indexOf('=');
        if (equals >= 0) {
            if (token.startsWith("columns=")) {
                int comma = token.lastIndexOf(',');
                if (comma > equals) {
                    return token.substring(0, comma + 1) + shared;
                }
            }
            return token.substring(0, equals + 1) + shared;
        }
        if (pathCandidate) {
            int slash = token.lastIndexOf('/');
            if (slash >= 0) {
                return token.substring(0, slash + 1) + shared;
            }
        }
        return shared;
    }

    private static String completionSuffix(String candidate) {
        if (candidate.endsWith("/") || candidate.endsWith("=")) {
            return "";
        }
        return " ";
    }

    private static List<String> matching(List<String> candidates, String fragment) {
        Set<String> matches = new LinkedHashSet<>();
        for (String candidate : candidates) {
            String value = candidate;
            if (candidate.startsWith(fragment)) {
                matches.add(value);
            }
        }
        return List.copyOf(matches);
    }

    private static String commonPrefix(List<String> values) {
        String prefix = values.getFirst();
        for (int index = 1; index < values.size(); index++) {
            String value = values.get(index);
            int length = Math.min(prefix.length(), value.length());
            int match = 0;
            while (match < length && prefix.charAt(match) == value.charAt(match)) {
                match++;
            }
            prefix = prefix.substring(0, match);
        }
        return prefix;
    }
}
