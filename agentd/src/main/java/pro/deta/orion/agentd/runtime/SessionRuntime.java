package pro.deta.orion.agentd.runtime;

@FunctionalInterface
public interface SessionRuntime {
    SessionLaunchResult launch(SessionSpec spec);
}
