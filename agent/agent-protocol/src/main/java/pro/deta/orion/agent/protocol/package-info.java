/**
 * Shared versioned contracts for AgentD and the central agent session server.
 * Incremental sequence callers must handle every recoverable issue and discard
 * or reset a decoder after a terminal issue.
 * Replication stream callers must additionally treat a rejected mixed-stream
 * item as fatal because skipping a journal record would break durable history.
 */
package pro.deta.orion.agent.protocol;
