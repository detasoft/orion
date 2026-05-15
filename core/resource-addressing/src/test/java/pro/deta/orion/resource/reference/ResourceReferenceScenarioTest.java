package pro.deta.orion.resource.reference;

import com.moandjiezana.toml.Toml;
import org.junit.jupiter.api.Test;
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
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceReferenceScenarioTest {
    private static final String RESOURCE_ROOT = "pro/deta/orion/resource/reference/scenarios/";
    private static final Yaml YAML_PARSER = new Yaml(new SafeConstructor(new LoaderOptions()));

    @Test
    void resolvesScenarioResourceFiles() throws Exception {
        for (String resourceName : List.of(
                "bootstrap-yaml.txt",
                "bootstrap-xml.txt",
                "bootstrap-toml.txt",
                "single-string.txt",
                "document-references.txt",
                "address-types.txt",
                "external-resolver.txt",
                "failure-required.txt",
                "failure-cycle.txt",
                "failure-document-source.txt",
                "failure-missing-document-path.txt",
                "failure-unreadable-path.txt",
                "failure-s3.txt")) {
            Scenario scenario = Scenario.load(resourceName);

            for (ExpectedCheck expected : scenario.expected()) {
                expected.verify(scenario);
            }
        }
    }

    private record Scenario(
            String resourceName,
            Map<String, String> environment,
            SourceFormat sourceFormat,
            Object source,
            List<ExpectedCheck> expected) {
        private static Scenario load(String resourceName) throws Exception {
            String raw = readResource(resourceName);
            Sections sections = Sections.parse(resourceName, raw);
            SourceFormat sourceFormat = SourceFormat.fromHeader(sections.sourceHeader());
            return new Scenario(
                    resourceName,
                    parseEnvironment(sections.environment()),
                    sourceFormat,
                    sourceFormat.parse(sections.source()),
                    parseExpected(sections.expected()));
        }

        private ResourceReferenceResolver resolver() {
            return resolver(ResourceResolverRegistry.builder().withDefaults().build());
        }

        private ResourceReferenceResolver resolver(ResourceResolverRegistry registry) {
            Map<String, String> context = new LinkedHashMap<>();
            flattenStringValues("", source, context);
            return ResourceReferenceResolver.builder()
                    .scope(ResourceReferenceScope.builder()
                            .environment(environment)
                            .context(context)
                            .build())
                    .registry(registry)
                    .build();
        }

        private Object sourceValue(String path) {
            if (".".equals(path)) {
                return source;
            }
            Object current = source;
            for (String segment : path.split("\\.")) {
                if (current instanceof Map<?, ?> map) {
                    current = map.get(segment);
                } else if (current instanceof List<?> list) {
                    current = list.get(Integer.parseInt(segment));
                } else {
                    throw new AssertionError("Cannot read " + path + " from " + resourceName);
                }
                if (current == null) {
                    throw new AssertionError("Missing source value " + path + " in " + resourceName);
                }
            }
            return current;
        }

        private static void flattenStringValues(String prefix, Object value, Map<String, String> context) {
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String name = prefix.isEmpty()
                            ? String.valueOf(entry.getKey())
                            : prefix + "." + entry.getKey();
                    flattenStringValues(name, entry.getValue(), context);
                }
            } else if (value instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    flattenStringValues(prefix + "." + i, list.get(i), context);
                }
            } else if (value instanceof String string) {
                context.put(prefix, string);
            }
        }
    }

    private enum SourceFormat {
        STRING {
            @Override
            Object parse(String source) {
                return source.strip();
            }
        },
        YAML {
            @Override
            Object parse(String source) {
                return YAML_PARSER.load(source);
            }
        },
        TOML {
            @Override
            Object parse(String source) {
                return new Toml().read(source).toMap();
            }
        },
        XML {
            @Override
            Object parse(String source) throws ParserConfigurationException, IOException, SAXException {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                Document document = factory.newDocumentBuilder()
                        .parse(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
                Element root = document.getDocumentElement();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put(root.getTagName(), xmlElementValue(root));
                return result;
            }
        };

        abstract Object parse(String source) throws Exception;

        private static SourceFormat fromHeader(String header) {
            return SourceFormat.valueOf(header.trim().toUpperCase());
        }
    }

    private interface ExpectedCheck {
        void verify(Scenario scenario);
    }

    private record ExpectedValue(String raw, String type, String path, Object expectedValue) implements ExpectedCheck {
        @Override
        public void verify(Scenario scenario) {
            assertThat(resolve(scenario, scenario.resolver(), type, path))
                    .describedAs("%s: %s", scenario.resourceName(), raw)
                    .isEqualTo(expectedValue);
        }
    }

    private record ExpectedFailure(
            String raw,
            String type,
            String path,
            String messageFragment) implements ExpectedCheck {
        @Override
        public void verify(Scenario scenario) {
            assertThatThrownBy(() -> resolve(scenario, scenario.resolver(), type, path))
                    .describedAs("%s: %s", scenario.resourceName(), raw)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining(messageFragment);
        }
    }

    private record ExpectedWithResolver(
            String raw,
            String type,
            String path,
            Object expectedValue) implements ExpectedCheck {
        @Override
        public void verify(Scenario scenario) {
            ResourceReferenceResolver resolver = scenario.resolver(ResourceResolverRegistry.builder()
                    .withDefaults()
                    .add(new RemoteGitRepositoryResolver())
                    .build());
            assertThat(resolve(scenario, resolver, type, path))
                    .describedAs("%s: %s", scenario.resourceName(), raw)
                    .isEqualTo(expectedValue);
        }
    }

    private static Object resolve(
            Scenario scenario,
            ResourceReferenceResolver resolver,
            String type,
            String path) {
        String rawValue = String.valueOf(scenario.sourceValue(path));
        return switch (type) {
            case "String" -> resolver.resolve(rawValue, String.class);
            case "Path" -> resolver.resolve(rawValue, Path.class);
            case "ResourceContent" -> contentFields(resolver.resolve(rawValue, ResourceContent.class));
            case "S3ObjectLocation" -> s3Fields(resolver.resolve(rawValue, S3ObjectLocation.class));
            case "GitRepositoryLocation" -> gitFields(resolver.resolve(rawValue, GitRepositoryLocation.class));
            case "RemoteGitRepository" -> remoteGitFields(resolver.resolve(rawValue, RemoteGitRepository.class));
            default -> throw new IllegalArgumentException("Unsupported expected type: " + type);
        };
    }

    private static Map<String, String> contentFields(ResourceContent content) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("text", content.asUtf8String());
        return fields;
    }

    private static Map<String, String> s3Fields(S3ObjectLocation location) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("bucket", location.bucket());
        fields.put("key", location.key());
        location.region().ifPresent(region -> fields.put("region", region));
        return fields;
    }

    private static Map<String, String> gitFields(GitRepositoryLocation location) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("kind", location.kind().name());
        fields.put("location", location.location());
        location.ref().ifPresent(ref -> fields.put("ref", ref));
        return fields;
    }

    private static Map<String, String> remoteGitFields(RemoteGitRepository repository) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("scheme", repository.scheme());
        fields.put("location", repository.location());
        fields.put("ref", repository.ref());
        return fields;
    }

    private record RemoteGitRepository(String scheme, String location, String ref) {
    }

    private static final class RemoteGitRepositoryResolver implements ResourceCapabilityResolver<RemoteGitRepository> {
        @Override
        public Class<RemoteGitRepository> targetType() {
            return RemoteGitRepository.class;
        }

        @Override
        public boolean supports(ResourceAddress address, ResourceReferenceScope scope) {
            return address.hasScheme(ResourceScheme.of("remotegit"));
        }

        @Override
        public RemoteGitRepository resolve(ResourceAddress address, ResourceReferenceScope scope) {
            return new RemoteGitRepository(
                    address.scheme().value(),
                    address.body(),
                    address.parameters().getOrDefault("ref", ""));
        }
    }

    private record Sections(
            String environment,
            String sourceHeader,
            String source,
            String expected) {
        private static Sections parse(String resourceName, String raw) {
            String environmentHeader = "=== ENVIRONMENT ===";
            String sourceHeaderPrefix = "=== SOURCE ";
            String expectedHeader = "=== EXPECTED ===";

            int environmentStart = raw.indexOf(environmentHeader);
            int sourceStart = raw.indexOf(sourceHeaderPrefix);
            int expectedStart = raw.indexOf(expectedHeader);
            if (environmentStart < 0 || sourceStart < 0 || expectedStart < 0) {
                throw new IllegalArgumentException("Scenario has missing sections: " + resourceName);
            }
            int sourceHeaderEnd = raw.indexOf("===", sourceStart + sourceHeaderPrefix.length());
            if (sourceHeaderEnd < 0) {
                throw new IllegalArgumentException("Scenario has invalid SOURCE header: " + resourceName);
            }

            String environment = raw.substring(
                    environmentStart + environmentHeader.length(),
                    sourceStart).strip();
            String sourceHeader = raw.substring(
                    sourceStart + sourceHeaderPrefix.length(),
                    sourceHeaderEnd).strip();
            String source = raw.substring(sourceHeaderEnd + "===".length(), expectedStart).strip();
            String expected = raw.substring(expectedStart + expectedHeader.length()).strip();
            return new Sections(environment, sourceHeader, source, expected);
        }
    }

    private static Map<String, String> parseEnvironment(String raw) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String line : lines(raw)) {
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Invalid environment line: " + line);
            }
            environment.put(line.substring(0, separator), decodeEscapes(line.substring(separator + 1)));
        }
        return environment;
    }

    private static List<ExpectedCheck> parseExpected(String raw) {
        List<ExpectedCheck> values = new ArrayList<>();
        for (String line : lines(raw)) {
            values.add(parseExpectedLine(line));
        }
        return values;
    }

    private static ExpectedCheck parseExpectedLine(String line) {
        if (line.startsWith("Failure ")) {
            return parseExpectedFailureLine(line);
        }
        if (line.startsWith("WithResolver ")) {
            return parseExpectedWithResolverLine(line);
        }

        int typeEnd = line.indexOf(' ');
        if (typeEnd <= 0) {
            throw new IllegalArgumentException("Invalid expected line: " + line);
        }

        String type = line.substring(0, typeEnd);
        String body = line.substring(typeEnd + 1).strip();
        if ("String".equals(type)) {
            int separator = body.indexOf('=');
            return new ExpectedValue(line, type, body.substring(0, separator), body.substring(separator + 1));
        }
        if ("Path".equals(type)) {
            int separator = body.indexOf('=');
            return new ExpectedValue(line, type, body.substring(0, separator), Path.of(body.substring(separator + 1)));
        }

        int pathEnd = body.indexOf(' ');
        if (pathEnd <= 0) {
            throw new IllegalArgumentException("Invalid expected object line: " + line);
        }
        return new ExpectedValue(
                line,
                type,
                body.substring(0, pathEnd),
                parseFields(body.substring(pathEnd + 1)));
    }

    private static ExpectedFailure parseExpectedFailureLine(String line) {
        String body = line.substring("Failure ".length()).strip();
        int typeEnd = body.indexOf(' ');
        if (typeEnd <= 0) {
            throw new IllegalArgumentException("Invalid expected failure line: " + line);
        }
        String type = body.substring(0, typeEnd);
        String rest = body.substring(typeEnd + 1).strip();
        int pathEnd = rest.indexOf(' ');
        if (pathEnd <= 0) {
            throw new IllegalArgumentException("Invalid expected failure line: " + line);
        }
        String path = rest.substring(0, pathEnd);
        String message = rest.substring(pathEnd + 1).strip();
        if (!message.startsWith("contains=")) {
            throw new IllegalArgumentException("Invalid expected failure message: " + line);
        }
        return new ExpectedFailure(line, type, path, message.substring("contains=".length()));
    }

    private static ExpectedWithResolver parseExpectedWithResolverLine(String line) {
        String body = line.substring("WithResolver ".length()).strip();
        int typeEnd = body.indexOf(' ');
        if (typeEnd <= 0) {
            throw new IllegalArgumentException("Invalid expected resolver line: " + line);
        }
        String type = body.substring(0, typeEnd);
        String rest = body.substring(typeEnd + 1).strip();
        int pathEnd = rest.indexOf(' ');
        if (pathEnd <= 0) {
            throw new IllegalArgumentException("Invalid expected resolver line: " + line);
        }
        return new ExpectedWithResolver(
                line,
                type,
                rest.substring(0, pathEnd),
                parseFields(rest.substring(pathEnd + 1)));
    }

    private static Map<String, String> parseFields(String raw) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String part : raw.split(" ")) {
            if (part.isBlank()) {
                continue;
            }
            int separator = part.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Invalid field: " + part);
            }
            fields.put(part.substring(0, separator), part.substring(separator + 1));
        }
        return fields;
    }

    private static String decodeEscapes(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                if (next == 'n') {
                    result.append('\n');
                } else if (next == 't') {
                    result.append('\t');
                } else if (next == 'r') {
                    result.append('\r');
                } else {
                    result.append(next);
                }
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static List<String> lines(String raw) {
        return raw.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("#"))
                .toList();
    }

    private static Object xmlElementValue(Element element) {
        NodeList children = element.getChildNodes();
        Map<String, Object> nested = new LinkedHashMap<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                nested.put(childElement.getTagName(), xmlElementValue(childElement));
            }
        }
        if (!nested.isEmpty()) {
            return nested;
        }
        return element.getTextContent().trim();
    }

    private static String readResource(String resourceName) throws IOException {
        String resourcePath = RESOURCE_ROOT + resourceName;
        URL resource = ResourceReferenceScenarioTest.class.getClassLoader().getResource(resourcePath);
        assertThat(resource).describedAs(resourcePath).isNotNull();
        try (InputStream input = resource.openStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
