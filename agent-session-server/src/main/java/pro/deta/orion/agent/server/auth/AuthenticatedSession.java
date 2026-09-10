package pro.deta.orion.agent.server.auth;

import pro.deta.orion.agent.server.connection.AgentControlHandler;

/** Internal session lifecycle reached only after authentication publication and handshake completion. */
interface AuthenticatedSession extends AgentControlHandler.Session {
    void onAuthenticated();
}
