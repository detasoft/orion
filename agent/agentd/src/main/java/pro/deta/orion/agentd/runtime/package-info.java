/**
 * Session runtimes and workspace resolution boundaries. A successful native
 * launch retains no process-lifetime handle in AgentD; only tentative launches
 * may be stopped during bounded pre-handoff cleanup.
 */
package pro.deta.orion.agentd.runtime;
