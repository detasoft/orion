package pro.deta.maven.rust;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.List;

/**
 * Reruns Cargo tests after clean when the normal test goal was restored from Maven Build Cache.
 */
@Mojo(name = "test-on-clean", defaultPhase = LifecyclePhase.TEST, threadSafe = true)
public final class CargoTestOnCleanMojo extends AbstractCargoMojo {
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    MavenSession session;

    @Override
    public void execute() throws MojoExecutionException {
        if (isSkipped()) {
            return;
        }
        boolean testAlreadyRan = Boolean.TRUE.equals(getPluginContext().get(CargoTestMojo.TEST_EXECUTED));
        if (shouldRun(session.getRequest().getGoals(), testAlreadyRan)) {
            runCargo("test");
        }
    }

    static boolean shouldRun(List<String> requestedGoals, boolean testAlreadyRan) {
        if (testAlreadyRan) {
            return false;
        }
        for (String requestedGoal : requestedGoals) {
            if ("clean".equals(requestedGoal) || requestedGoal.endsWith(":clean")) {
                return true;
            }
        }
        return false;
    }
}
