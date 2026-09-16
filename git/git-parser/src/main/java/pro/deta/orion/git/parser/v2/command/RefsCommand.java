package pro.deta.orion.git.parser.v2.command;

/**
 * Produces repository ref views for ls-refs and initial advertisement using the bound repository context.
 * Owns ref selection, HEAD resolution, and tag peeling so both operations reuse the same interpretation.
 * Protocol capability selection stays with session configuration and byte encoding stays with the writer.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code lsRefs(LsRefsRequest)} - return refs matching the requested prefixes and attributes.</li>
 *   <li>{@code advertiseRefs(...)} - return the ref data required for an initial advertisement.</li>
 *   <li>{@code effectiveHeadTarget(...)} - privately resolve the advertised HEAD target.</li>
 *   <li>{@code peeledObjectId(...)} - privately resolve annotated-tag targets.</li>
 * </ul>
 * Method names and signatures are provisional; existing peeling helpers can remain private implementation.
 */
public final class RefsCommand implements GitCommand {
}
