package pro.deta.orion.git;

import lombok.Getter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import pro.deta.orion.git.util.GitUtils;
import pro.deta.orion.test.util.ResourceUtils;

public class BaseOrionTest {
    @Getter
    private TestInfo testInfo;

    @BeforeAll
    public static void configureLogger() {
        ResourceUtils.configureDefaultLogging();
        GitUtils.gitConfigure(new PropertyOrionJGitSystemReader(null));
    }

    @BeforeEach
    void init(TestInfo testInfo) {
        this.testInfo = testInfo;
    }
}
