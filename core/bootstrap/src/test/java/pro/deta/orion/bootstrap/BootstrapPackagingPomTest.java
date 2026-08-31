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
        Path pom = bootstrapPom();
        String xml = Files.readString(pom);

        assertTrue(xml.contains("${project.build.finalName}-executable.jar"));
        assertTrue(xml.contains("<classifier>executable</classifier>"));
        assertTrue(xml.contains("${project.build.finalName}-executable.jar.sha256"));
        assertFalse(xml.contains("${project.build.finalName}-initd.jar"));
        assertFalse(xml.contains("<classifier>initd</classifier>"));
    }

    @Test
    void jlinkDistributionIsLimitedToDistProfile() throws IOException {
        Path pom = bootstrapPom();
        String xml = Files.readString(pom);

        int profile = xml.indexOf("<id>dist</id>");
        assertTrue(profile >= 0);

        int jlink = xml.indexOf("${java.home}/bin/jlink");
        assertTrue(jlink > profile);

        int nextProfile = xml.indexOf("<profile>", profile + 1);
        if (nextProfile >= 0) {
            assertTrue(jlink < nextProfile);
        }

        assertTrue(xml.contains("${project.build.directory}/orion-dist"));
        assertTrue(xml.contains("${project.build.directory}/orion-dist/runtime"));
        assertTrue(xml.contains("${project.build.directory}/orion-dist/lib/orion.jar"));
        assertTrue(xml.contains("${project.build.directory}/orion-dist.tar.gz"));
        assertTrue(xml.contains("<classifier>dist</classifier>"));
        assertTrue(xml.contains("src/main/dist/bin/orion"));
    }

    private static Path bootstrapPom() {
        Path reactorRelativePom = Path.of("core", "bootstrap", "pom.xml");
        if (Files.exists(reactorRelativePom)) {
            return reactorRelativePom;
        }
        return Path.of("pom.xml");
    }
}
