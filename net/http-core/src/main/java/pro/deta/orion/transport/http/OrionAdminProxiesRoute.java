package pro.deta.orion.transport.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.LoggerFactory;
import pro.deta.orion.config.OrionConfigurationEditor;
import pro.deta.orion.config.OrionConfigurationConcurrentUpdateException;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.command.audit.CommandAuditRecord;
import pro.deta.orion.command.audit.CommandAuditSink;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory.SyncObservation;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.orion.v2.GitCredentialKind;
import pro.deta.orion.schema.orion.v2.GitProxyBinding;
import pro.deta.orion.schema.orion.v2.Connection;
import pro.deta.orion.schema.orion.v2.ConnectionReference;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.RemoteAlias;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Administers system proxy bindings through revision-checked configuration updates and safe audit records. */
public final class OrionAdminProxiesRoute extends BaseAdminRoute {
    private final OrionDesiredState desiredState;
    private final NativeGitRepositoryFactory provider;
    private final OrionConfigurationEditor editor;
    private final ConfigurationSecrets secrets;
    private final CommandAuditSink audit;
    private final ObjectMapper mapper;

    @Inject
    public OrionAdminProxiesRoute(OrionDesiredState desiredState, NativeGitRepositoryFactory provider,
            OrionConfigurationEditor editor, ConfigurationSecrets secrets,
            CommandAuditSink audit, ObjectMapper mapper) {
        super(OrionAdminPaths.PROXIES, OrionHttpRouteDefinition.Method.GET, OrionHttpRouteDefinition.Method.POST);
        this.desiredState = desiredState;
        this.provider = provider;
        this.editor = editor;
        this.secrets = secrets;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) {
        var snapshot = desiredState.current();
        var aliases = new ArrayList<AliasResponse>();
        for (GitProxyBinding binding : snapshot.document().system().proxies()) {
            aliases.add(project(binding, snapshot.document().system(), provider.syncObservation(binding,
                    snapshot.document().system())));
        }
        aliases.sort(Comparator.comparing(AliasResponse::alias));
        return OrionHttpResponse.ok(new AliasListResponse(aliases, snapshot.revision().orElse(null)));
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest req) {
        long started = System.nanoTime();
        MutationRequest request = null;
        String action = "invalid";
        String alias = "";
        String resultCode = "configuration-unavailable";
        OrionHttpResponse response = OrionHttpResponse.json(503, Map.of("status", resultCode));
        try {
            request = mapper.readValue(req.getInputStream(), MutationRequest.class);
            if (request == null || request.action() == null || !"system".equals(request.scope())
                    || request.alias() == null || request.revision() == null || request.revision().isBlank()
                    || !Set.of("create", "update", "replace-credential", "retry").contains(request.action())) {
                throw new IllegalArgumentException("Invalid proxy mutation");
            }
            action = request.action();
            RemoteAlias selected = new RemoteAlias(request.alias());
            alias = selected.value();
            if (action.equals("retry")) {
                if (request.credential() != null || request.upstream() != null || request.ref() != null
                        || request.credentialKind() != null || request.username() != null || request.knownHosts() != null) {
                    throw new IllegalArgumentException("Retry accepts no configuration changes");
                }
                if (!desiredState.current().revision().equals(Optional.of(request.revision()))) {
                    throw new OrionConfigurationConcurrentUpdateException("Configuration revision changed", null);
                }
            } else {
                MutationRequest mutation = request;
                SecurityContext context = (SecurityContext) req.getAttribute(
                        OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
                editor.edit(request.revision()).update(document -> update(document, selected, mutation))
                        .apply("proxy " + action + " " + alias,
                                new UserEmail(context.getUserIdentity().getUserId(), ""));
            }
            OrionDesiredState.Snapshot snapshot = desiredState.current();
            GitProxyBinding binding = find(snapshot.document(), selected);
            SyncObservation observation = retry(snapshot, binding);
            String status = action.equals("retry") ? "retried" : "saved";
            resultCode = status + ":" + wireStatus(observation);
            response = OrionHttpResponse.json(action.equals("create") ? 201 : 200,
                    new MutationResponse(status, project(binding, snapshot.document().system(), observation),
                            snapshot.revision().orElse(null)));
        } catch (OrionConfigurationConcurrentUpdateException failure) {
            try {
                editor.reload("proxy configuration conflict");
            } catch (RuntimeException reloadFailure) {
                LoggerFactory.getLogger(OrionAdminProxiesRoute.class).warn("Could not reload proxy configuration");
            }
            resultCode = "configuration-conflict";
            response = OrionHttpResponse.json(409, Map.of("status", resultCode));
        } catch (Rejected failure) {
            resultCode = failure.getMessage();
            response = OrionHttpResponse.json(400, Map.of("status", resultCode));
        } catch (IOException | IllegalArgumentException failure) {
            resultCode = "invalid-request";
            response = OrionHttpResponse.json(400, Map.of("status", resultCode));
        } catch (RuntimeException failure) {
            resultCode = "configuration-unavailable";
            response = OrionHttpResponse.json(503, Map.of("status", resultCode));
        } finally {
            if (request != null && request.credential() != null) {
                Arrays.fill(request.credential(), '\0');
            }
            recordAudit(req, action, alias, response.status(), resultCode, System.nanoTime() - started);
        }
        return response;
    }

    private SyncObservation retry(OrionDesiredState.Snapshot snapshot, GitProxyBinding binding) {
        if (!desiredState.current().revision().equals(snapshot.revision())) {
            throw new OrionConfigurationConcurrentUpdateException("Configuration changed before retry", null);
        }
        Result<SyncObservation> result = provider.retry(binding.alias(),
                () -> desiredState.current().document(), secrets);
        if (!desiredState.current().revision().equals(snapshot.revision())) {
            throw new OrionConfigurationConcurrentUpdateException("Configuration changed during retry", null);
        }
        if (result instanceof Result.Success<SyncObservation> success) return success.value();
        return provider.syncObservation(binding, snapshot.document().system());
    }

    private OrionDocument update(OrionDocument document, RemoteAlias alias, MutationRequest request) {
        GitProxyBinding existing = null;
        for (GitProxyBinding binding : document.system().proxies()) {
            if (binding.alias().equals(alias)) existing = binding;
        }
        boolean create = request.action().equals("create");
        boolean replace = request.action().equals("replace-credential");
        if (create && (request.upstream() == null || request.ref() == null)) {
            throw new Rejected("upstream-and-ref-required");
        }
        if (create == (existing != null)) throw new Rejected(create ? "alias-exists" : "alias-not-found");
        if ((!create && !replace && request.credential() != null) || (replace && request.credential() == null)) {
            throw new Rejected("explicit-credential-replacement-required");
        }
        URI upstream = request.upstream() == null ? existing.upstream(document.system()) : URI.create(request.upstream());
        String ref = request.ref() == null ? existing.ref() : request.ref();
        GitCredentialKind kind = request.credentialKind() == null
                ? (existing == null ? GitCredentialKind.NONE : existing.credentialKind(document.system()))
                : GitCredentialKind.valueOf(request.credentialKind());
        Optional<String> secret = existing == null ? Optional.empty() : existing.secret(document.system());
        boolean newSecret = secret.isEmpty();
        if (replace && secret.isPresent()) {
            for (GitProxyBinding binding : document.system().proxies()) {
                if (!binding.alias().equals(alias) && binding.secret(document.system()).equals(secret)) newSecret = true;
            }
            for (Connection connection : document.system().connections()) {
                boolean own = existing != null && existing.source() instanceof GitProxyBinding.Ssh ssh
                        && ssh.connection().name().equals(connection.name());
                if (!own && connection.referencesSecret(secret.orElseThrow())) newSecret = true;
            }
            if (document.system().https().flatMap(value -> value.acme()).flatMap(value -> value.eabSecret())
                    .equals(secret)) newSecret = true;
        }
        if (kind == GitCredentialKind.NONE) {
            if (request.credential() != null) throw new Rejected("credential-not-supported");
            secret = Optional.empty();
        } else if (newSecret) {
            if (request.credential() == null) throw new Rejected("credential-required");
            secret = Optional.of("proxy-" + UUID.randomUUID());
        }
        Optional<String> username = request.username() == null
                ? (existing == null ? Optional.empty() : existing.username(document.system())) : Optional.of(request.username());
        if (kind != GitCredentialKind.PASSWORD || "ssh".equalsIgnoreCase(upstream.getScheme())) {
            username = Optional.empty();
        }
        URI previousUpstream = existing == null ? null : existing.upstream(document.system());
        URI selectedUpstream = GitProxyBinding.canonicalUpstream(upstream);
        boolean sameSshServer = previousUpstream != null && "ssh".equals(previousUpstream.getScheme())
                && "ssh".equals(selectedUpstream.getScheme())
                && previousUpstream.getHost().equals(selectedUpstream.getHost())
                && previousUpstream.getPort() == selectedUpstream.getPort();
        Set<String> knownHosts = request.knownHosts() == null
                ? (sameSshServer ? existing.knownHosts(document.system()) : Set.of())
                : request.knownHosts();
        if (!"ssh".equalsIgnoreCase(upstream.getScheme())) knownHosts = Set.of();
        List<Connection> connections = new ArrayList<>(document.system().connections());
        GitProxyBinding.Source transport;
        if ("ssh".equalsIgnoreCase(upstream.getScheme())) {
            String connectionName = existing != null && existing.source() instanceof GitProxyBinding.Ssh ssh
                    ? ssh.connection().name() : alias.value() + "-ssh";
            Connection.Ssh replacementConnection = Connection.Ssh.fromUpstream(connectionName, upstream, kind,
                    secret, knownHosts);
            if (existing != null && existing.source() instanceof GitProxyBinding.Ssh) {
                Connection.Ssh previousConnection = existing.sshConnection(document.system());
                if (!previousConnection.equals(replacementConnection) || replace) {
                    for (GitProxyBinding other : document.system().proxies()) {
                        if (!other.alias().equals(alias) && other.source() instanceof GitProxyBinding.Ssh otherSsh
                                && otherSsh.connection().name().equals(connectionName)) {
                            throw new Rejected("shared-connection-requires-explicit-edit");
                        }
                    }
                }
                connections.set(connections.indexOf(previousConnection), replacementConnection);
            } else {
                connections.add(replacementConnection);
            }
            transport = new GitProxyBinding.Ssh(
                    new ConnectionReference(ConnectionReference.Scope.SYSTEM, connectionName), upstream.getRawPath());
        } else {
            transport = new GitProxyBinding.Direct(upstream, kind, secret, username);
        }
        GitProxyBinding replacement = new GitProxyBinding(alias, transport, ref);
        if (existing != null && provider.isBootstrapSource(existing, document.system())
                && (!existing.upstream(document.system()).equals(GitProxyBinding.canonicalUpstream(upstream))
                        || !existing.ref().equals(replacement.ref()))) {
            throw new Rejected("bootstrap-source-fixed");
        }
        var bindings = new ArrayList<GitProxyBinding>();
        for (GitProxyBinding binding : document.system().proxies()) {
            if (!binding.alias().equals(alias)) {
                if (binding.upstream(document.system()).equals(GitProxyBinding.canonicalUpstream(upstream))
                        && binding.ref().equals(replacement.ref())) {
                    throw new Rejected("upstream-already-bound");
                }
                bindings.add(binding);
            }
        }
        bindings.add(replacement);
        if (request.credential() != null) {
            if (request.credential().length == 0) throw new Rejected("credential-required");
            String id = secret.orElseThrow();
            document = newSecret ? secrets.createSystem(document, id, request.credential())
                    : secrets.replaceSystem(document, id, request.credential());
        }
        OrionDocument candidate = new OrionDocument(new OrionDocument.SystemConfiguration(
                document.system().accessControl(), document.system().https(), document.system().secrets(), bindings, connections),
                document.organizations());
        secrets.validate(candidate);
        return candidate;
    }

    private static GitProxyBinding find(OrionDocument document, RemoteAlias alias) {
        for (GitProxyBinding binding : document.system().proxies()) {
            if (binding.alias().equals(alias)) return binding;
        }
        throw new Rejected("alias-not-found");
    }

    private void recordAudit(HttpServletRequest req, String action, String alias, int status,
            String resultCode, long elapsed) {
        try {
            var context = (SecurityContext) req.getAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
            audit.record(new CommandAuditRecord(context.getUserIdentity().getUserId(), UUID.randomUUID().toString(),
                    "", req.getRemoteAddr(), OrionAdminPaths.PROXIES, action, Map.of("alias", alias),
                    status < 400 ? "success" : "failed", resultCode, elapsed,
                    Map.of("transport", "http", "scope", "system")));
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(OrionAdminProxiesRoute.class).warn("Could not record proxy administration audit");
        }
    }

    private static AliasResponse project(GitProxyBinding binding, OrionDocument.SystemConfiguration system,
            SyncObservation observation) {
        return new AliasResponse("system", binding.alias().value(), sanitizedUpstream(binding.upstream(system)),
                binding.upstream(system).getScheme(), binding.ref(), "/r/" + binding.publicRepositoryName() + ".git",
                wireStatus(observation),
                observation.observedAt() == null ? null : observation.observedAt().toString());
    }

    private static String wireStatus(SyncObservation observation) {
        return observation.status().name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String sanitizedUpstream(URI upstream) {
        if (upstream.getRawUserInfo() == null) return upstream.toASCIIString();
        String authority = upstream.getRawAuthority();
        return upstream.getScheme() + "://" + authority.substring(authority.lastIndexOf('@') + 1)
                + upstream.getRawPath();
    }

    private static final class Rejected extends RuntimeException {
        private Rejected(String code) { super(code); }
    }

    public record MutationRequest(String action, String scope, String revision, String alias,
            String upstream, String ref, String credentialKind, String username, Set<String> knownHosts,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) char[] credential) {
        @Override public String toString() { return "ProxyMutation[redacted]"; }
    }

    public record MutationResponse(String status, AliasResponse alias, String revision) { }
    public record AliasListResponse(List<AliasResponse> aliases, String revision) { }
    public record AliasResponse(String scope, String alias, String upstream, String transport, String ref,
            String endpoint, String status, String observedAt) { }
}
