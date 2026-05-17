package pro.deta.orion.resource.reference;

import com.moandjiezana.toml.Toml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ResourceReferenceResolver {
    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final ResourceReferenceScope scope;
    private final ResourceResolverRegistry registry;

    private ResourceReferenceResolver(ResourceReferenceScope scope, ResourceResolverRegistry registry) {
        this.scope = scope == null ? ResourceReferenceScope.empty() : scope;
        this.registry = registry == null ? ResourceResolverRegistry.builder().withDefaults().build() : registry;
    }

    public static ResourceReferenceResolver standard(ResourceReferenceScope scope) {
        return new ResourceReferenceResolver(scope, ResourceResolverRegistry.builder().withDefaults().build());
    }

    public static Builder builder() {
        return new Builder();
    }

    public <T> T resolve(String raw, Class<T> targetType) {
        return resolve(ResourceReference.parse(raw), targetType);
    }

    public <T> T resolve(ResourceReference reference, Class<T> targetType) {
        if (reference == null) {
            throw new IllegalArgumentException("Resource reference must not be null");
        }
        if (targetType == null) {
            throw new IllegalArgumentException("Resource reference target type must not be null");
        }

        Evaluator evaluator = new Evaluator();
        Object value;
        if (String.class.equals(targetType)) {
            value = evaluator.string(reference.root());
        } else if (Path.class.equals(targetType)) {
            value = evaluator.path(reference.root());
        } else if (ResourceContent.class.equals(targetType)) {
            value = evaluator.content(reference.root(), reference.raw());
        } else {
            value = evaluator.capability(reference.root(), targetType);
        }
        return targetType.cast(value);
    }

    ResourceReferenceScope scope() {
        return scope;
    }

    public static final class Builder {
        private ResourceReferenceScope scope = ResourceReferenceScope.empty();
        private ResourceResolverRegistry registry = ResourceResolverRegistry.builder().withDefaults().build();

        public Builder scope(ResourceReferenceScope scope) {
            this.scope = scope;
            return this;
        }

        public Builder registry(ResourceResolverRegistry registry) {
            this.registry = registry;
            return this;
        }

        public ResourceReferenceResolver build() {
            return new ResourceReferenceResolver(scope, registry);
        }
    }

    private final class Evaluator {
        private final Deque<String> contextStack = new ArrayDeque<>();

        private String string(ReferenceNode node) {
            return switch (node) {
                case LiteralReferenceNode literal -> literal.value();
                case VariableReferenceNode variable -> variable(variable);
                case InterpolatedReferenceNode interpolation -> interpolation.parts().stream()
                        .map(this::string)
                        .reduce("", String::concat);
                case AddressReferenceNode address -> addressText(address);
                case DocumentReferenceNode document -> documentValue(document);
            };
        }

        private Path path(ReferenceNode node) {
            if (node instanceof AddressReferenceNode address && address.scheme().equals(ResourceScheme.FILE)) {
                return Path.of(partsToString(address.body()));
            }
            return Path.of(string(node));
        }

        private ResourceContent content(ReferenceNode node, String sourceName) {
            if (node instanceof AddressReferenceNode address) {
                Optional<ResourceContent> extensionContent = capabilityIfAvailable(
                        ResourceAddress.parse(addressText(address)),
                        ResourceContent.class);
                if (extensionContent.isPresent()) {
                    return extensionContent.get();
                }
                if (address.scheme().equals(ResourceScheme.CONTENT)) {
                    return new ResourceContent(sourceName, partsToString(address.body()).getBytes(StandardCharsets.UTF_8));
                }
                if (address.scheme().equals(ResourceScheme.FILE)) {
                    return readFile(Path.of(partsToString(address.body())), sourceName);
                }
                if (address.scheme().equals(ResourceScheme.HTTP) || address.scheme().equals(ResourceScheme.HTTPS)) {
                    return readHttp(addressText(address), sourceName);
                }
            }
            String value = string(node);
            Optional<ResourceContent> extensionContent = capabilityIfAvailable(
                    ResourceAddress.parse(value),
                    ResourceContent.class);
            if (extensionContent.isPresent()) {
                return extensionContent.get();
            }
            if (value.startsWith("http://") || value.startsWith("https://")) {
                return readHttp(value, sourceName);
            }
            if (isObviousInlineContent(value)) {
                return new ResourceContent(sourceName, value.getBytes(StandardCharsets.UTF_8));
            }
            Optional<Path> path = pathIfReadable(value);
            if (path.isPresent()) {
                return readFile(path.get(), sourceName);
            }
            if (looksLikePathOrUri(value)) {
                throw new ResourceReferenceResolutionException("Cannot read document source: " + sourceName);
            }
            return new ResourceContent(sourceName, value.getBytes(StandardCharsets.UTF_8));
        }

        private Object capability(ReferenceNode node, Class<?> targetType) {
            ResourceAddress address = ResourceAddress.parse(string(node));
            Optional<?> resolved = capabilityIfAvailable(address, targetType);
            if (resolved.isPresent()) {
                return resolved.get();
            }
            throw new ResourceReferenceResolutionException(
                    "No resource capability resolver for " + targetType.getName() + ": " + address.raw());
        }

        private <T> Optional<T> capabilityIfAvailable(ResourceAddress address, Class<T> targetType) {
            ResourceCapabilityResolver<?> matchingResolver = null;
            for (ResourceCapabilityResolver<?> capabilityResolver : registry.resolvers()) {
                if (!targetType.equals(capabilityResolver.targetType())
                        || !capabilityResolver.supports(address, scope)) {
                    continue;
                }
                if (matchingResolver != null) {
                    throw new ResourceReferenceResolutionException(
                            "Ambiguous resource capability resolvers for " + targetType.getName()
                                    + ": " + address.raw());
                }
                matchingResolver = capabilityResolver;
            }
            if (matchingResolver != null) {
                return Optional.of(targetType.cast(resolveCapability(matchingResolver, address)));
            }
            return Optional.empty();
        }

        private Object resolveCapability(ResourceCapabilityResolver<?> capabilityResolver, ResourceAddress address) {
            return capabilityResolver.resolve(address, scope);
        }

        private String variable(VariableReferenceNode variable) {
            Lookup lookup = lookup(variable.name());
            boolean useFallback = switch (variable.operator().orElse(null)) {
                case DEFAULT_IF_UNSET_OR_BLANK -> lookup.unset() || lookup.value().isBlank();
                case DEFAULT_IF_UNSET -> lookup.unset();
                case ERROR_IF_UNSET_OR_BLANK -> {
                    if (lookup.unset() || lookup.value().isBlank()) {
                        throw required(variable);
                    }
                    yield false;
                }
                case ERROR_IF_UNSET -> {
                    if (lookup.unset()) {
                        throw required(variable);
                    }
                    yield false;
                }
                case null -> {
                    if (lookup.unset()) {
                        throw new ResourceReferenceResolutionException(
                                "Resource reference variable is not set: " + variable.name());
                    }
                    yield false;
                }
            };

            if (useFallback) {
                return evaluateOperand(variable.operatorOperand().orElse(""));
            }
            return lookup.value();
        }

        private Lookup lookup(String name) {
            if (name.contains(".")) {
                return lookupContext(name);
            }

            String environmentValue = scope.environment().get(name);
            if (environmentValue != null) {
                return Lookup.set(environmentValue);
            }

            String contextValue = scope.context().get(name);
            if (contextValue != null) {
                return Lookup.set(evaluateContext(name, contextValue));
            }

            return Lookup.missing();
        }

        private Lookup lookupContext(String name) {
            String contextValue = scope.context().get(name);
            if (contextValue == null) {
                return Lookup.missing();
            }
            return Lookup.set(evaluateContext(name, contextValue));
        }

        private String evaluateContext(String name, String value) {
            if (contextStack.contains(name)) {
                throw new ResourceReferenceResolutionException("Resource reference cycle detected: "
                        + String.join(" -> ", cycle(name)));
            }
            if (contextStack.size() >= scope.maxDepth()) {
                throw new ResourceReferenceResolutionException("Resource reference nesting is too deep: " + name);
            }

            contextStack.addLast(name);
            try {
                return string(ResourceReference.parse(value).root());
            } finally {
                contextStack.removeLast();
            }
        }

        private List<String> cycle(String name) {
            List<String> stack = contextStack.stream().toList();
            int start = stack.indexOf(name);
            if (start < 0) {
                return List.of(name);
            }
            java.util.ArrayList<String> cycle = new java.util.ArrayList<>(stack.subList(start, stack.size()));
            cycle.add(name);
            return cycle;
        }

        private String evaluateOperand(String raw) {
            if (raw.isEmpty()) {
                return "";
            }
            return string(ResourceReference.parse(raw).root());
        }

        private ResourceReferenceResolutionException required(VariableReferenceNode variable) {
            String message = evaluateOperand(variable.operatorOperand().orElse(""));
            if (message.isBlank()) {
                message = "Required resource reference is missing: " + variable.name();
            }
            return new ResourceReferenceResolutionException(message);
        }

        private String addressText(AddressReferenceNode address) {
            return address.scheme().value() + ":" + partsToString(address.body());
        }

        private String partsToString(List<ReferenceNode> parts) {
            StringBuilder result = new StringBuilder();
            for (ReferenceNode part : parts) {
                result.append(string(part));
            }
            return result.toString();
        }

        private String documentValue(DocumentReferenceNode document) {
            ResourceContent content = documentSource(document.source());
            Object node = parseDocument(document.format(), content);
            for (String segment : document.path().segments()) {
                node = selectDocumentSegment(node, segment);
                if (node == null) {
                    throw new ResourceReferenceResolutionException(
                            "Document path does not exist: " + document.path());
                }
            }
            return documentNodeText(node);
        }

        private ResourceContent documentSource(ReferenceNode source) {
            if (source instanceof AddressReferenceNode || source instanceof InterpolatedReferenceNode) {
                return content(source, "document source");
            }
            String value = string(source);
            if (isObviousInlineContent(value)) {
                return new ResourceContent("document source", value.getBytes(StandardCharsets.UTF_8));
            }
            Optional<Path> path = pathIfReadable(value);
            if (path.isPresent()) {
                return readFile(path.get(), "document source");
            }
            if (looksLikePathOrUri(value)) {
                throw new ResourceReferenceResolutionException("Cannot read document source: " + value);
            }
            return new ResourceContent("document source", value.getBytes(StandardCharsets.UTF_8));
        }

        private Object parseDocument(DocumentFormat format, ResourceContent content) {
            try {
                return switch (format) {
                    case YAML -> YAML.load(content.asUtf8String());
                    case XML -> parseXml(content.bytes());
                    case TOML -> new Toml().read(content.asUtf8String()).toMap();
                };
            } catch (RuntimeException | IOException | ParserConfigurationException | SAXException e) {
                throw new ResourceReferenceResolutionException(
                        "Cannot parse " + format.name().toLowerCase() + " document source: "
                                + content.sourceName(),
                        e);
            }
        }

        private Object selectDocumentSegment(Object node, String segment) {
            if (node instanceof Document document) {
                Element root = document.getDocumentElement();
                if (root == null) {
                    return null;
                }
                return root.getTagName().equals(segment) ? root : selectXmlChild(root, segment);
            }
            if (node instanceof Element element) {
                if (segment.startsWith("@")) {
                    String attribute = segment.substring(1);
                    return element.hasAttribute(attribute) ? element.getAttribute(attribute) : null;
                }
                return selectXmlChild(element, segment);
            }
            if (node instanceof Map<?, ?> map) {
                return map.get(segment);
            }
            if (node instanceof List<?> list) {
                return selectListElement(list, segment);
            }
            return null;
        }

        private Object selectXmlChild(Element element, String segment) {
            NodeList children = element.getChildNodes();
            List<Element> matches = new ArrayList<>();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element childElement && childElement.getTagName().equals(segment)) {
                    matches.add(childElement);
                }
            }
            if (matches.isEmpty()) {
                return null;
            }
            return matches.size() == 1 ? matches.getFirst() : matches;
        }

        private Object selectListElement(List<?> list, String segment) {
            try {
                int index = Integer.parseInt(segment);
                if (index < 0 || index >= list.size()) {
                    return null;
                }
                return list.get(index);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private String documentNodeText(Object node) {
            if (node == null) {
                return "";
            }
            if (node instanceof Element element) {
                return xmlElementText(element);
            }
            return String.valueOf(node);
        }

        private String xmlElementText(Element element) {
            NodeList children = element.getChildNodes();
            boolean hasElementChild = false;
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element) {
                    hasElementChild = true;
                    break;
                }
            }
            return hasElementChild ? element.getTextContent() : element.getTextContent().trim();
        }
    }

    private static Document parseXml(byte[] content)
            throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
    }

    private static ResourceContent readFile(Path path, String sourceName) {
        try {
            return new ResourceContent(sourceName, Files.readAllBytes(path));
        } catch (IOException e) {
            throw new ResourceReferenceResolutionException("Cannot read document source: " + path, e);
        }
    }

    private static ResourceContent readHttp(String value, String sourceName) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(value)).GET().build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResourceReferenceResolutionException(
                        "HTTP resource returned status " + response.statusCode() + ": " + value);
            }
            return new ResourceContent(sourceName, response.body());
        } catch (IOException e) {
            throw new ResourceReferenceResolutionException("Cannot read HTTP resource: " + value, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResourceReferenceResolutionException("Interrupted while reading HTTP resource: " + value, e);
        } catch (IllegalArgumentException e) {
            throw new ResourceReferenceResolutionException("Invalid HTTP resource: " + value, e);
        }
    }

    private static Optional<Path> pathIfReadable(String value) {
        try {
            Path path = Path.of(value);
            if (Files.isRegularFile(path) && Files.isReadable(path)) {
                return Optional.of(path);
            }
            return Optional.empty();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static boolean isObviousInlineContent(String value) {
        String trimmed = value.stripLeading();
        return value.contains("\n")
                || trimmed.startsWith("{")
                || trimmed.startsWith("[")
                || trimmed.startsWith("<");
    }

    private static boolean looksLikePathOrUri(String value) {
        if (value.contains("/") || value.contains("\\")) {
            return true;
        }
        if (value.startsWith("file:")
                || value.startsWith("s3:")
                || value.startsWith("http:")
                || value.startsWith("https:")
                || value.startsWith("git:")) {
            return true;
        }
        try {
            URI uri = new URI(value);
            return uri.getScheme() != null && value.contains("://");
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private record Lookup(boolean unset, String value) {
        static Lookup missing() {
            return new Lookup(true, "");
        }

        static Lookup set(String value) {
            return new Lookup(false, value == null ? "" : value);
        }
    }
}
