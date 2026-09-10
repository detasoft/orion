package pro.deta.maven.rust;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Runs Cargo tests and records their execution for the clean-cache companion goal.
 */
@Mojo(name = "test", defaultPhase = LifecyclePhase.TEST, threadSafe = true)
public final class CargoTestMojo extends AbstractCargoMojo {
    static final String TEST_EXECUTED = CargoTestMojo.class.getName() + ".executed";

    @Override
    @SuppressWarnings("unchecked")
    public void execute() throws MojoExecutionException {
        if (isSkipped()) {
            return;
        }
        runCargo("test");
        getPluginContext().put(TEST_EXECUTED, true);
    }
}
