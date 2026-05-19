package pro.deta.orion.resource.reference;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import pro.deta.orion.resource.reference.generated.ResourceReferenceBaseVisitor;
import pro.deta.orion.resource.reference.generated.ResourceReferenceLexer;

import java.util.List;
import java.util.Optional;

final class ResourceReferenceParser {
    private ResourceReferenceParser() {
    }

    static ResourceReference parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Resource reference must not be empty");
        }

        ResourceReferenceLexer lexer = new ResourceReferenceLexer(CharStreams.fromString(raw));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new ThrowingErrorListener(raw));

        pro.deta.orion.resource.reference.generated.ResourceReferenceParser parser =
                new pro.deta.orion.resource.reference.generated.ResourceReferenceParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(new ThrowingErrorListener(raw));
        parser.setErrorHandler(new BailErrorStrategy());

        try {
            ReferenceNode root = new AstBuilder().visit(parser.reference());
            return new ResourceReference(raw, root);
        } catch (ParseCancellationException e) {
            throw parseException(raw, e);
        }
    }

    private static IllegalArgumentException parseException(String raw, RuntimeException e) {
        if (looksLikeDocumentReferenceWithPlainSource(raw)) {
            return new IllegalArgumentException("Document source must be a nested reference: " + raw, e);
        }
        return new IllegalArgumentException("Invalid resource reference: " + raw, e);
    }

    private static boolean looksLikeDocumentReferenceWithPlainSource(String raw) {
        for (String prefix : List.of("${yaml:", "${toml:", "${xml:")) {
            if (raw.startsWith(prefix)) {
                return raw.length() <= prefix.length() || raw.charAt(prefix.length()) != '$';
            }
        }
        return false;
    }

    private static final class AstBuilder extends ResourceReferenceBaseVisitor<ReferenceNode> {
        @Override
        public ReferenceNode visitReference(
                pro.deta.orion.resource.reference.generated.ResourceReferenceParser.ReferenceContext ctx) {
            if (ctx.addressReference() != null) {
                return visitAddressReference(ctx.addressReference());
            }
            return visitInterpolation(ctx.interpolation());
        }

        @Override
        public ReferenceNode visitInterpolation(
                pro.deta.orion.resource.reference.generated.ResourceReferenceParser.InterpolationContext ctx) {
            List<ReferenceNode> parts = ctx.interpolationPart().stream()
                    .map(this::visitInterpolationPart)
                    .toList();
            if (parts.size() == 1) {
                return parts.getFirst();
            }
            return new InterpolatedReferenceNode(parts);
        }

        @Override
        public ReferenceNode visitInterpolationPart(
                pro.deta.orion.resource.reference.generated.ResourceReferenceParser.InterpolationPartContext ctx) {
            if (ctx.bracedReference() != null) {
                return visitBracedReference(ctx.bracedReference());
            }
            if (ctx.shorthandVariable() != null) {
                return visitShorthandVariable(ctx.shorthandVariable());
            }
            return visitLiteral(ctx.literal());
        }

        @Override
        public ReferenceNode visitBracedReference(
                pro.deta.orion.resource.reference.generated.ResourceReferenceParser.BracedReferenceContext ctx) {
            return visitBracedBody(ctx.bracedBody());
        }

        @Override
        public ReferenceNode visitBracedBody(
                pro.deta.orion.resource.reference.generated.ResourceReferenceParser.BracedBodyContext ctx) {
            if (ctx.documentReference() != null) {
                return visitDocumentReference(ctx.documentReference());
            }
            if (ctx.variableExpansion() != null) {
                return visitVariableExpansion(ctx.variableExpansion());
            }
            AddressReferenceNode address = (AddressReferenceNode) visitAddressReference(ctx.addressReference());
            if (isDocumentScheme(address.scheme())) {
                throw new IllegalArgumentException("Document source must be a nested reference: " + ctx.getText());
            }
            return address;
        }

        @Override
        public ReferenceNode visitDocumentReference(
                pro.deta.orion.resource.reference.generated.ResourceReferenceParser.DocumentReferenceContext ctx) {
            DocumentFormat format = DocumentFormat.valueOf(ctx.documentFormat().getText().toUpperCase());
            ReferenceNode source = visitDocumentSource(ctx.documentSource());
            DocumentPath path = DocumentPath.parse(ctx.documentPath().getText());
            return new DocumentReferenceNode(format, source, path);
        }

        @Override
        public ReferenceNode visitDocumentSource(
                pro.deta.orion.resource.reference.generated.ResourceReferenceParser.DocumentSourceContext ctx) {
            if (ctx.bracedReference() != null) {
                return visitBracedReference(ctx.bracedReference());
            }
            return visitShorthandVariable(ctx.shorthandVariable());
        }

        @Override
        public ReferenceNode visitVariableExpansion(
                pro.deta.orion.resource.reference.generated.ResourceReferenceParser.VariableExpansionContext ctx) {
            String name = ctx.variableName().getText();
            if (ctx.parameterExpansion() == null) {
                return new VariableReferenceNode(name);
            }
            ParameterExpansion parameter = parameter(ctx.parameterExpansion().getText());
            return new VariableReferenceNode(
                    name,
                    Optional.of(parameter.operator()),
                    Optional.of(parameter.operand()));
        }

        @Override
        public ReferenceNode visitAddressReference(
                pro.deta.orion.resource.reference.generated.ResourceReferenceParser.AddressReferenceContext ctx) {
            ResourceScheme scheme = ResourceScheme.of(ctx.scheme().getText());
            List<ReferenceNode> body = ctx.addressBody().interpolationPart().stream()
                    .map(this::visitInterpolationPart)
                    .toList();
            return new AddressReferenceNode(scheme, body);
        }

        @Override
        public ReferenceNode visitShorthandVariable(
                pro.deta.orion.resource.reference.generated.ResourceReferenceParser.ShorthandVariableContext ctx) {
            return new VariableReferenceNode(ctx.identifier().getText());
        }

        @Override
        public ReferenceNode visitLiteral(
                pro.deta.orion.resource.reference.generated.ResourceReferenceParser.LiteralContext ctx) {
            return new LiteralReferenceNode(ctx.getText());
        }

        private static boolean isDocumentScheme(ResourceScheme scheme) {
            String value = scheme.value();
            return "yaml".equals(value) || "toml".equals(value) || "xml".equals(value);
        }

        private static ParameterExpansion parameter(String raw) {
            if (raw.startsWith(":-")) {
                return new ParameterExpansion(
                        ParameterExpansionOperator.DEFAULT_IF_UNSET_OR_BLANK,
                        raw.substring(2));
            }
            if (raw.startsWith(":?")) {
                return new ParameterExpansion(
                        ParameterExpansionOperator.ERROR_IF_UNSET_OR_BLANK,
                        raw.substring(2));
            }
            if (raw.startsWith("-")) {
                return new ParameterExpansion(ParameterExpansionOperator.DEFAULT_IF_UNSET, raw.substring(1));
            }
            if (raw.startsWith("?")) {
                return new ParameterExpansion(ParameterExpansionOperator.ERROR_IF_UNSET, raw.substring(1));
            }
            throw new IllegalArgumentException("Unsupported parameter expansion: " + raw);
        }
    }

    private record ParameterExpansion(ParameterExpansionOperator operator, String operand) {
    }

    private static final class ThrowingErrorListener extends BaseErrorListener {
        private final String raw;

        private ThrowingErrorListener(String raw) {
            this.raw = raw;
        }

        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String msg,
                RecognitionException e) {
            throw new IllegalArgumentException(
                    "Invalid resource reference at " + line + ":" + charPositionInLine + " in " + raw + ": " + msg,
                    e);
        }
    }
}
