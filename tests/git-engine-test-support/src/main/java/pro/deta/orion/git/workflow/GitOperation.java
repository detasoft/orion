package pro.deta.orion.git.workflow;

@FunctionalInterface
public interface GitOperation {
    GitOperationResult run() throws Exception;
}
