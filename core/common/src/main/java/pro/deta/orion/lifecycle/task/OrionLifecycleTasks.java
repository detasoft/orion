package pro.deta.orion.lifecycle.task;

public final class OrionLifecycleTasks {
    public static final LifecycleTaskId JGIT_RUNTIME = new LifecycleTaskId("JGIT_RUNTIME");
    public static final LifecycleTaskId JGIT_RUNTIME_STOP = new LifecycleTaskId("JGIT_RUNTIME_STOP");
    public static final LifecycleTaskId EVENT_MANAGER = new LifecycleTaskId("EVENT_MANAGER");
    public static final LifecycleTaskId ACL_LOAD = new LifecycleTaskId("ACL_LOAD");
    public static final LifecycleTaskId TRANSPORTS_START = new LifecycleTaskId("TRANSPORTS_START");
    public static final LifecycleTaskId TRANSPORT_LIFECYCLE_START = new LifecycleTaskId("TRANSPORT_LIFECYCLE_START");
    public static final LifecycleTaskId TRANSPORTS_STOP = new LifecycleTaskId("TRANSPORTS_STOP");
    public static final LifecycleTaskId TRANSPORT_LIFECYCLE_STOP = new LifecycleTaskId("TRANSPORT_LIFECYCLE_STOP");
    public static final LifecycleTaskId EVENT_MANAGER_STOP = new LifecycleTaskId("EVENT_MANAGER_STOP");
    public static final LifecycleTaskId EXECUTOR_STOP = new LifecycleTaskId("EXECUTOR_STOP");

    private OrionLifecycleTasks() {
    }
}
