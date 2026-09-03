/**
 * Shared versioned contracts for AgentD and the central agent session server.
 * Incremental sequence callers must handle every recoverable issue and discard
 * or reset a decoder after a terminal issue.
 */
package pro.deta.orion.agent.protocol;
