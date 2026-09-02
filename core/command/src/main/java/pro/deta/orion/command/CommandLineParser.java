package pro.deta.orion.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CommandLineParser {
    public CommandParseResult parse(String commandLine, CommandPath currentPath) {
        Objects.requireNonNull(commandLine, "commandLine");
        Objects.requireNonNull(currentPath, "currentPath");
        Tokenization tokenization = tokenize(commandLine);
        if (tokenization.failure() != null) {
            return tokenization.failure();
        }
        List<Token> tokens = tokenization.tokens();
        if (tokens.isEmpty()) {
            return failure("Command line is empty", 0);
        }

        int actionIndex = actionIndex(tokens);
        if (actionIndex > 1) {
            return failure(
                    "Unexpected token before action; only one path token is allowed",
                    tokens.get(1).position());
        }
        CommandParseResult pathResult = parsePath(tokens, actionIndex, currentPath);
        if (pathResult instanceof CommandParseResult.Failure) {
            return pathResult;
        }
        CommandPath path = ((CommandParseResult.Success) pathResult).command().path();
        if (actionIndex < 0) {
            return failure("Command path has no action", tokens.getFirst().position());
        }

        String action = tokens.get(actionIndex).text();
        List<String> positional = new ArrayList<>();
        Map<String, String> named = new LinkedHashMap<>();
        List<WherePredicate> predicates = new ArrayList<>();
        boolean where = false;
        for (int index = actionIndex + 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (!where && token.text().equals("where")) {
                where = true;
                if (index == tokens.size() - 1) {
                    return failure("Where clause requires a predicate", token.position());
                }
                continue;
            }
            if (where) {
                CommandParseResult predicateFailure = addPredicate(token, predicates);
                if (predicateFailure != null) {
                    return predicateFailure;
                }
                continue;
            }
            int equals = token.text().indexOf('=');
            if (equals < 0) {
                positional.add(token.text());
                continue;
            }
            if (equals == 0) {
                return failure("Named parameter has an empty name", token.position());
            }
            String name = token.text().substring(0, equals);
            String value = token.text().substring(equals + 1);
            if (named.putIfAbsent(name, value) != null) {
                return failure("Duplicate named parameter: " + name, token.position());
            }
        }
        return new CommandParseResult.Success(new ParsedCommand(path, action, positional, named, predicates));
    }

    private static int actionIndex(List<Token> tokens) {
        Token first = tokens.getFirst();
        if (CommandAction.fromValue(first.text()).isPresent()) {
            return 0;
        }
        for (int index = 1; index < tokens.size(); index++) {
            if (CommandAction.fromValue(tokens.get(index).text()).isPresent()) {
                return index;
            }
        }
        if (!looksLikePath(first.text())) {
            return 0;
        }
        return -1;
    }

    private static boolean looksLikePath(String token) {
        return token.startsWith("/") || token.startsWith(".") || token.indexOf('/') >= 0;
    }

    private static CommandParseResult parsePath(
            List<Token> tokens,
            int actionIndex,
            CommandPath currentPath) {
        if (!currentPath.absolute()) {
            return failure("Current path must be absolute", 0);
        }
        if (actionIndex == 0) {
            return parsedPath(currentPath);
        }
        Token pathToken = tokens.getFirst();
        List<String> segments = new ArrayList<>();
        boolean absolute = pathToken.text().startsWith("/");
        if (!absolute) {
            segments.addAll(currentPath.segments());
        }
        String[] rawSegments = pathToken.text().split("/", -1);
        for (String segment : rawSegments) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    return failure("Path traverses above root", pathToken.position());
                }
                segments.removeLast();
            } else {
                segments.add(segment);
            }
        }
        return parsedPath(CommandPath.absolute(segments));
    }

    private static CommandParseResult parsedPath(CommandPath path) {
        return new CommandParseResult.Success(new ParsedCommand(path, "", List.of(), Map.of(), List.of()));
    }

    private static CommandParseResult addPredicate(Token token, List<WherePredicate> predicates) {
        String text = token.text();
        int operator = text.indexOf("!=");
        WherePredicate.Operator predicateOperator = WherePredicate.Operator.NOT_EQUALS;
        int operatorLength = 2;
        if (operator < 0) {
            operator = text.indexOf('=');
            predicateOperator = WherePredicate.Operator.EQUALS;
            operatorLength = 1;
        }
        if (operator <= 0 || operator + operatorLength >= text.length()) {
            return failure("Invalid where predicate", token.position());
        }
        String field = text.substring(0, operator);
        String value = text.substring(operator + operatorLength);
        predicates.add(new WherePredicate(field, predicateOperator, value));
        return null;
    }

    private static Tokenization tokenize(String commandLine) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        int tokenStart = -1;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < commandLine.length(); index++) {
            char character = commandLine.charAt(index);
            if (escaped) {
                value.append(character);
                escaped = false;
                continue;
            }
            if (character == '\\') {
                if (tokenStart < 0) {
                    tokenStart = index;
                }
                escaped = true;
                continue;
            }
            if (quote != 0) {
                if (character == quote) {
                    quote = 0;
                } else {
                    value.append(character);
                }
                continue;
            }
            if (character == '\'' || character == '"') {
                if (tokenStart < 0) {
                    tokenStart = index;
                }
                quote = character;
            } else if (Character.isWhitespace(character)) {
                tokenStart = finishToken(tokens, value, tokenStart);
            } else {
                if (tokenStart < 0) {
                    tokenStart = index;
                }
                value.append(character);
            }
        }
        if (escaped) {
            return Tokenization.failed(failure("Trailing escape", commandLine.length() - 1));
        }
        if (quote != 0) {
            return Tokenization.failed(failure("Unterminated quote", Math.max(tokenStart, 0)));
        }
        finishToken(tokens, value, tokenStart);
        return Tokenization.succeeded(tokens);
    }

    private static int finishToken(List<Token> tokens, StringBuilder value, int tokenStart) {
        if (tokenStart >= 0) {
            tokens.add(new Token(value.toString(), tokenStart));
            value.setLength(0);
        }
        return -1;
    }

    private static CommandParseResult.Failure failure(String message, int position) {
        return new CommandParseResult.Failure(CommandFailureCode.INVALID_SYNTAX, message, position);
    }

    private record Token(String text, int position) {}

    private record Tokenization(List<Token> tokens, CommandParseResult.Failure failure) {
        private static Tokenization succeeded(List<Token> tokens) {
            return new Tokenization(List.copyOf(tokens), null);
        }

        private static Tokenization failed(CommandParseResult.Failure failure) {
            return new Tokenization(List.of(), failure);
        }
    }
}
