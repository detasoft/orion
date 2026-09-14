package pro.deta.orion.git.parser.v2;

/**
 * Binds a server operation to one normalized repository path, resolved repository, and access context.
 * Preserves publication through the policy-aware provider. Separate HTTP discovery and POST requests receive
 * separate contexts; per-want and per-ref authorization remains with the corresponding command.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code open(...)} - resolve or create the repository once and check operation-level access.</li>
 *   <li>{@code repositoryPath()} - return the normalized repository path.</li>
 *   <li>{@code repository()} - return the repository bound to this operation.</li>
 *   <li>{@code accessHook()} - provide the bound access policy for command-specific checks.</li>
 *   <li>{@code publish(...)} - publish through the existing provider policy using the bound path.</li>
 * </ul>
 * Method names and signatures are provisional. Resource cleanup is added only for resources the context owns;
 * this context must not become a service containing the implementations of every command.
 */
public final class GitRepositoryContext {
}
