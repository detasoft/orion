/**
 * Local session discovery, state caching, and session-host control.
 *
 * <p>Native control v1 validates liveness with STATUS. For INPUT/RESIZE/SIGNAL/
 * TERMINATE Java now sends schema-2 with operation sequence and command envelope
 * for host dedupe, and retries these operations consistently after ambiguous
 * delivery. STATUS remains schema-1.</p>
 */
package pro.deta.orion.agentd.session;
