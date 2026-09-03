/**
 * Local session discovery, state caching, and session-host control.
 *
 * <p>Native control v1 proves liveness with STATUS but provides no durable host
 * incarnation identifier. PID equality is therefore correlation, not identity
 * proof. The protocol also has no general operation sequence: INPUT retries use
 * their native UUID, while other commands are not replayed after ambiguous
 * delivery. Named-pipe transport remains unavailable until the native Windows
 * host defines a timeout-capable boundary.</p>
 */
package pro.deta.orion.agentd.session;
