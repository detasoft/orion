/**
 * Local session discovery, state caching, and session-host control.
 *
 * <p>Native control v1 validates liveness with schema-1 STATUS. Schema-3 operations carry
 * an explicit source and their operation sequence in the frame header, then receive a
 * transient admission response. Durable outcomes are read from the session journal. The
 * schema-1 server-control claim atomically recovers the host's accepted SERVER sequence and
 * applied journal-acknowledgement watermark before command delivery resumes.</p>
 */
package pro.deta.orion.agentd.session;
