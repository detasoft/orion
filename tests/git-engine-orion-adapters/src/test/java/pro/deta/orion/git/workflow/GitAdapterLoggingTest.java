package pro.deta.orion.git.workflow;

import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

@DisabledIfSystemProperty(named = "orion.test.debug", matches = "(?i)true")
class GitAdapterLoggingTest {
    @ParameterizedTest
    @ValueSource(strings = {"org.eclipse.jetty", "org.eclipse.jgit", "org.apache.sshd"})
    void suppressesDependencyDebugLoggingByDefault(String category) {
        Logger logger = LoggerFactory.getLogger(category);

        assertThat(logger.isDebugEnabled()).as("DEBUG logging for %s", category).isFalse();
        assertThat(logger.isInfoEnabled()).as("INFO logging for %s", category).isTrue();
    }
}
