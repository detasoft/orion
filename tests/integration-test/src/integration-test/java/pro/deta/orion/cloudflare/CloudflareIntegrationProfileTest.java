package pro.deta.orion.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class CloudflareIntegrationProfileTest {

    private static final String ENABLED_PROPERTY = "cloudflare.it.enabled";

    @Test
    void cloudflareMutationRequiresDistProfile() throws IOException {
        EnabledIfSystemProperty condition =
                IntegrationCloudflareIT.class.getAnnotation(EnabledIfSystemProperty.class);

        assertNotNull(condition);
        assertEquals(ENABLED_PROPERTY, condition.named());
        assertEquals("true", condition.matches());

        String pom = Files.readString(integrationTestPom());
        String property = "<" + ENABLED_PROPERTY + ">";
        String closingProperty = "</" + ENABLED_PROPERTY + ">";

        assertTrue(pom.contains(property + "false" + closingProperty));
        assertTrue(pom.contains(property + "${" + ENABLED_PROPERTY + "}" + closingProperty));

        int distProfile = pom.indexOf("<id>dist</id>");
        assertTrue(distProfile >= 0);

        int enabledInDist = pom.indexOf(property + "true" + closingProperty, distProfile);
        assertTrue(enabledInDist > distProfile);

        int distProfileEnd = pom.indexOf("</profile>", distProfile);
        assertTrue(distProfileEnd > distProfile);
        assertTrue(enabledInDist < distProfileEnd);
    }

    private static Path integrationTestPom() throws IOException {
        Path reactorRelativePom = Path.of("tests", "integration-test", "pom.xml");
        if (Files.exists(reactorRelativePom)) {
            return reactorRelativePom;
        }

        Path modulePom = Path.of("pom.xml");
        if (Files.exists(modulePom)
                && Files.readString(modulePom).contains("<artifactId>integration-test</artifactId>")) {
            return modulePom;
        }

        return Path.of("..", "pom.xml");
    }
}
