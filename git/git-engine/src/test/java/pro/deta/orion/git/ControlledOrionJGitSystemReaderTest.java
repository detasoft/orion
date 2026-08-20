package pro.deta.orion.git;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.config.schema.OrionConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Controlled JGit system reader")
class ControlledOrionJGitSystemReaderTest {
    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("does not read Git config files from home or XDG directories")
    void doesNotReadGitConfigFilesFromHomeOrXdgDirectories() throws Exception {
        Path home = tempDir.resolve("home");
        Path xdg = tempDir.resolve("xdg");
        Files.createDirectories(home.resolve(".config/jgit"));
        Files.createDirectories(xdg.resolve("git"));
        Files.createDirectories(xdg.resolve("jgit"));
        Files.writeString(home.resolve(".gitconfig"), "[user]\n\tname = home-user\n");
        Files.writeString(home.resolve(".jgitconfig"), "[core]\n\ttrustFolderStat = false\n");
        Files.writeString(xdg.resolve("git/config"), "[user]\n\temail = xdg@example.test\n");
        Files.writeString(xdg.resolve("jgit/config"), "[core]\n\tautocrlf = true\n");

        OrionConfiguration.JGitConfig config = new OrionConfiguration.JGitConfig();
        config.getProperties().put("user.home", home.toString());
        config.getEnvironment().put(Constants.XDG_CONFIG_HOME, xdg.toString());
        ControlledOrionJGitSystemReader reader = new ControlledOrionJGitSystemReader(config);

        StoredConfig userConfig = reader.getUserConfig();
        StoredConfig jgitConfig = reader.getJGitConfig();

        assertThat(userConfig.getString("user", null, "name")).isNull();
        assertThat(userConfig.getString("user", null, "email")).isNull();
        assertThat(jgitConfig.getString("core", null, "trustFolderStat")).isNull();
        assertThat(jgitConfig.getString("core", null, "autocrlf")).isNull();
    }

    @Test
    @DisplayName("uses only config values supplied through Orion API")
    void usesOnlyConfigValuesSuppliedThroughOrionApi() throws Exception {
        OrionConfiguration.JGitConfig config = new OrionConfiguration.JGitConfig();
        config.getSystemConfig().put("receive.denyNonFastForwards", "true");
        config.getUserConfig().put("user.name", "orion-user");
        config.getUserConfig().put("http.https://example.test.proxy", "http://proxy.example.test");
        config.getJgitConfig().put("core.trustFolderStat", "false");
        ControlledOrionJGitSystemReader reader = new ControlledOrionJGitSystemReader(config);

        assertThat(reader.getSystemConfig().getBoolean("receive", null, "denyNonFastForwards", false)).isTrue();
        assertThat(reader.getUserConfig().getString("user", null, "name")).isEqualTo("orion-user");
        assertThat(reader.getUserConfig().getString("http", "https://example.test", "proxy"))
                .isEqualTo("http://proxy.example.test");
        assertThat(reader.getJGitConfig().getBoolean("core", null, "trustFolderStat", true)).isFalse();
    }

    @Test
    @DisplayName("does not fall back to JVM properties or process environment")
    void doesNotFallBackToJvmPropertiesOrProcessEnvironment() {
        String property = "orion.jgit.leak.test";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "jvm-value");
            OrionConfiguration.JGitConfig config = new OrionConfiguration.JGitConfig();
            ControlledOrionJGitSystemReader reader = new ControlledOrionJGitSystemReader(config);

            assertThat(reader.getProperty(property)).isNull();
            assertThat(reader.getenv("PATH")).isNull();

            config.getProperties().put(property, "api-value");
            config.getEnvironment().put("PATH", "/api/bin");
            reader = new ControlledOrionJGitSystemReader(config);

            assertThat(reader.getProperty(property)).isEqualTo("api-value");
            assertThat(reader.getenv("PATH")).isEqualTo("/api/bin");
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    @DisplayName("provides deterministic runtime defaults for JGit")
    void providesDeterministicRuntimeDefaultsForJGit() {
        ControlledOrionJGitSystemReader reader = new ControlledOrionJGitSystemReader(new OrionConfiguration.JGitConfig());

        assertThat(reader.getHostname()).isEqualTo("localhost");
        assertThat(reader.getProperty("os.name")).isEqualTo("Linux");
        assertThat(reader.getProperty("native.encoding")).isEqualTo("UTF-8");
        assertThat(reader.getDefaultCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(reader.getTimeZone()).isEqualTo(TimeZone.getTimeZone("UTC"));
        assertThat(reader.getLocale()).isEqualTo(Locale.ROOT);
    }
}
