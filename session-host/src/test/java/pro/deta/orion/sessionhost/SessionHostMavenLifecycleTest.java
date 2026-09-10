package pro.deta.orion.sessionhost;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SessionHostMavenLifecycleTest {

    @Test
    void bindsCargoToMavenLifecycleAndPackagesTheNativeBinary() throws IOException {
        String pom = Files.readString(repositoryFile("session-host/pom.xml"));

        assertTrue(pom.contains("<artifactId>rust-maven-plugin</artifactId>"));
        assertExecution(pom, "cargo-build", "compile", "build");
        assertExecution(pom, "cargo-test", "test", "test");
        assertExecution(pom, "cargo-test-on-clean", "test", "test-on-clean");
        assertTrue(pom.contains("${project.build.outputDirectory}/META-INF/orion/native/session-host"));
        assertTrue(pom.contains("<binary>session-host</binary>"));
    }

    @Test
    void usesEveryModuleFileAsCacheInputAndForcesCleanReconciliation() throws IOException {
        String pom = Files.readString(repositoryFile("session-host/pom.xml"));
        String extensions = Files.readString(repositoryFile(".mvn/extensions.xml"));
        String cache = Files.readString(repositoryFile(".mvn/maven-build-cache-config.xml"));

        assertTrue(extensions.contains("<artifactId>maven-build-cache-extension</artifactId>"));
        assertTrue(pom.contains("<maven.build.cache.input.1>.</maven.build.cache.input.1>"));
        assertTrue(pom.contains("<maven.build.cache.input.2>src</maven.build.cache.input.2>"));
        assertTrue(pom.contains("<maven.build.cache.input.3>tests</maven.build.cache.input.3>"));
        assertTrue(pom.contains("<maven.build.cache.input.4>protocol</maven.build.cache.input.4>"));
        assertTrue(pom.contains(
                "<maven.build.cache.exclude.value.1>target</maven.build.cache.exclude.value.1>"));
        assertTrue(cache.contains("<execId>cargo-test-on-clean</execId>"));
        assertTrue(cache.contains("<excludeProperty>orion.testDurations.runId</excludeProperty>"));
    }

    @Test
    void distUsesCargoReleaseAndRoutineMakeTestUsesOnlyTheReactor() throws IOException {
        String pom = Files.readString(repositoryFile("session-host/pom.xml"));
        String makefile = Files.readString(repositoryFile("Makefile"));

        int distProfile = pom.indexOf("<id>dist</id>");
        assertTrue(distProfile >= 0);
        assertTrue(pom.indexOf("<rust.release>true</rust.release>", distProfile) > distProfile);
        assertTrue(makefile.contains("test:\n\t$(MAVEN) test -Pdev -T 4"));
        assertFalse(makefile.contains("test: session-host-test"));
    }

    private static void assertExecution(String pom, String id, String phase, String goal) {
        int execution = pom.indexOf("<id>" + id + "</id>");
        assertTrue(execution >= 0);
        int end = pom.indexOf("</execution>", execution);
        assertTrue(end > execution);
        String xml = pom.substring(execution, end);
        assertTrue(xml.contains("<phase>" + phase + "</phase>"));
        assertTrue(xml.contains("<goal>" + goal + "</goal>"));
    }

    private static Path repositoryFile(String relativePath) {
        Path fromRoot = Path.of(relativePath);
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        return Path.of("..").resolve(relativePath).normalize();
    }
}
