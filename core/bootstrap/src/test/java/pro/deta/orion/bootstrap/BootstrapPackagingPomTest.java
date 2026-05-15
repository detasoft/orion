package pro.deta.orion.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BootstrapPackagingPomTest {

    @Test
    void attachesSingleExecutableArtifact() throws IOException {
        Path pom = Path.of("core", "bootstrap", "pom.xml");
        String xml = Files.readString(pom);

        assertTrue(xml.contains("${project.build.finalName}-executable.jar"));
        assertTrue(xml.contains("<classifier>executable</classifier>"));
        assertTrue(xml.contains("${project.build.finalName}-executable.jar.sha256"));
        assertFalse(xml.contains("${project.build.finalName}-initd.jar"));
        assertFalse(xml.contains("<classifier>initd</classifier>"));
    }
}
