package pro.deta.orion.transport.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.slf4j.LoggerFactory;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.storage.AccessControlConcurrentUpdateException;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.command.audit.CommandAuditRecord;
import pro.deta.orion.command.audit.CommandAuditSink;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.decision.DecisionRequiredException;
import pro.deta.orion.decision.Decisionable;
import pro.deta.orion.git.client.GitSshClientTransport.HostKeyRejectedException;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider.SyncObservation;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.orion.GitCredentialKind;
import pro.deta.orion.schema.orion.GitProxyBinding;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.schema.orion.RemoteAlias;
import pro.deta.orion.ssh.SshHostKeyDecision;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Administers system proxy bindings through revision-checked configuration updates and safe audit records. */
public final class OrionAdminProxiesRoute extends BaseAdminRoute {
    private final OrionDesiredState desiredState;
    private final ProxyAwareNativeGitRepositoryProvider provider;
    private final OrionAccessControlServiceImpl acl;
    private final ConfigurationSecrets secrets;
    private final BootstrapRepositorySources sources;
    private final CommandAuditSink audit;
    private final ObjectMapper mapper;
    private final DecisionRegistry decisions;

    @Inject
    public OrionAdminProxiesRoute(OrionDesiredState desiredState, ProxyAwareNativeGitRepositoryProvider provider,
            OrionAccessControlServiceImpl acl, ConfigurationSecrets secrets, BootstrapRepositorySources sources,
            CommandAuditSink audit, ObjectMapper mapper, DecisionRegistry decisions) {
        super(OrionAdminPaths.PROXIES, OrionHttpRouteDefinition.Method.GET, OrionHttpRouteDefinition.Method.POST);
        this.desiredState = desiredState;
        this.provider = provider;
        this.acl = acl;
        this.secrets = secrets;
        this.sources = sources;
        this.audit = audit;
        this.mapper = mapper;
        this.decisions = decisions;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) {
        var snapshot = desiredState.current();
        var aliases = new ArrayList<AliasResponse>();
        for (GitProxyBinding binding : snapshot.document().system().proxies()) {
            aliases.add(project(binding, provider.syncObservation(binding)));
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
                    throw new AccessControlConcurrentUpdateException("Configuration revision changed", null);
                }
            } else {
                MutationRequest mutation = request;
                acl.updatePrimaryConfiguration(request.revision(), document -> update(document, selected, mutation),
                        new AccessControlSaveRequest("proxy " + action + " " + alias, UserEmail.EMPTY));
            }
            OrionDesiredState.Snapshot snapshot = desiredState.current();
            GitProxyBinding binding = find(snapshot.document(), selected);
            SyncObservation observation = retry(snapshot, binding);
            String status = action.equals("retry") ? "retried" : "saved";
            resultCode = status + ":" + wireStatus(observation);
            response = OrionHttpResponse.json(action.equals("create") ? 201 : 200,
                    new MutationResponse(status, project(binding, observation), snapshot.revision().orElse(null)));
        } catch (AccessControlConcurrentUpdateException failure) {
            try {
                acl.reload("proxy configuration conflict");
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
            throw new AccessControlConcurrentUpdateException("Configuration changed before retry", null);
        }
        Result<SyncObservation> result = provider.retry(binding.alias(),
                () -> desiredState.current().document(), secrets);
        if (!desiredState.current().revision().equals(snapshot.revision())) {
            throw new AccessControlConcurrentUpdateException("Configuration changed during retry", null);
        }
        if (result instanceof Result.Success<SyncObservation> success) return success.value();
        Throwable failure = prepareDecision(snapshot, binding, ((Result.Failure<SyncObservation>) result).throwable());
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof Decisionable required) {
                Result<Decision> registered = decisions.register(required.decision());
                if (registered instanceof Result.Failure<Decision>) {
                    throw new IllegalStateException("Could not register connection decision", failure);
                }
                break;
            }
        }
        return provider.syncObservation(binding);
    }

    private Throwable prepareDecision(OrionDesiredState.Snapshot snapshot,
            GitProxyBinding binding, Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof Decisionable) return failure;
            if (!(cause instanceof HostKeyRejectedException rejected)) continue;
            int port = binding.upstream().getPort() < 0 ? 22 : binding.upstream().getPort();
            if (!binding.upstream().getHost().equalsIgnoreCase(rejected.host()) || port != rejected.port()) {
                throw new IllegalStateException("Rejected host key belongs to another server", failure);
            }
            String key = PublicKeyEntry.toString(rejected.serverKey());
            Result<SshHostKeyDecision> prepared = SshHostKeyDecision.create(binding.alias(),
                    Optional.empty(),
                    rejected.host(), rejected.port(), rejected.serverKey(),
                    actor -> trust(snapshot, binding, key, actor));
            if (prepared instanceof Result.Failure<SshHostKeyDecision>) {
                throw new IllegalStateException("Could not prepare SSH host key decision", failure);
            }
            SshHostKeyDecision decision = ((Result.Success<SshHostKeyDecision>) prepared).value();
            return new DecisionRequiredException(decision, failure);
        }
        return failure;
    }

    private Result<Void> trust(OrionDesiredState.Snapshot snapshot,
            GitProxyBinding binding, String key, PrincipalAddress actor) {
        try {
            Set<String> keys = new TreeSet<>(binding.knownHosts());
            keys.add(key);
            GitProxyBinding replacement = new GitProxyBinding(binding.alias(), binding.upstream(), binding.ref(),
                    binding.credentialKind(), binding.secret(), binding.username(), keys);
            acl.updatePrimaryConfiguration(snapshot.revision().orElseThrow(), document -> {
                if (!document.system().proxies().contains(binding)) {
                    throw new AccessControlConcurrentUpdateException("Connection configuration changed", null);
                }
                List<GitProxyBinding> bindings = new ArrayList<>(document.system().proxies());
                bindings.set(bindings.indexOf(binding), replacement);
                OrionDocument.SystemConfiguration system = document.system();
                return new OrionDocument(new OrionDocument.SystemConfiguration(system.accessControl(),
                        system.https(), system.secrets(), bindings), document.organizations());
            }, new AccessControlSaveRequest("Trust SSH host key for " + binding.alias().value()
                    + " approved by " + actor, UserEmail.EMPTY));
            recordTrustAudit(binding, actor, "saved");
            return Result.of(null);
        } catch (AccessControlConcurrentUpdateException failure) {
            recordTrustAudit(binding, actor, "configuration-conflict");
            return new Result.Failure<>(Result.FailureCode.GENERAL, "Connection configuration changed", failure);
        } catch (RuntimeException failure) {
            recordTrustAudit(binding, actor, "operation-failed");
            return new Result.Failure<>(Result.FailureCode.GENERAL, "Could not save SSH host key", failure);
        }
    }

    private void recordTrustAudit(GitProxyBinding binding, PrincipalAddress actor, String result) {
        try {
            audit.record(new CommandAuditRecord(actor.toString(), UUID.randomUUID().toString(),
                    "", "", OrionAdminPaths.PROXIES, "trust-host-key", Map.of("alias", binding.alias().value()),
                    result.equals("saved") ? "success" : "failed", result, 0, Map.of("scope", "system")));
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(OrionAdminProxiesRoute.class).warn("Could not record SSH trust operation audit");
        }
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
        URI upstream = request.upstream() == null ? existing.upstream() : URI.create(request.upstream());
        String ref = request.ref() == null ? existing.ref() : request.ref();
        GitCredentialKind kind = request.credentialKind() == null
                ? (existing == null ? GitCredentialKind.NONE : existing.credentialKind())
                : GitCredentialKind.valueOf(request.credentialKind());
        Optional<String> secret = existing == null ? Optional.empty() : existing.secret();
        boolean newSecret = secret.isEmpty();
        if (replace && secret.isPresent()) {
            for (GitProxyBinding binding : document.system().proxies()) {
                if (!binding.alias().equals(alias) && binding.secret().equals(secret)) newSecret = true;
            }
        }
        if (kind == GitCredentialKind.NONE) {
            if (request.credential() != null) throw new Rejected("credential-not-supported");
            secret = Optional.empty();
        } else if (newSecret) {
            if (request.credential() == null) throw new Rejected("credential-required");
            secret = Optional.of("proxy-" + UUID.randomUUID());
        }
        Optional<String> username = request.username() == null
                ? (existing == null ? Optional.empty() : existing.username()) : Optional.of(request.username());
        if (kind != GitCredentialKind.PASSWORD || "ssh".equalsIgnoreCase(upstream.getScheme())) {
            username = Optional.empty();
        }
        Set<String> knownHosts = request.knownHosts() == null
                ? (existing == null || !existing.upstream().equals(GitProxyBinding.canonicalUpstream(upstream))
                        ? Set.of() : existing.knownHosts())
                : request.knownHosts();
        if (!"ssh".equalsIgnoreCase(upstream.getScheme())) knownHosts = Set.of();
        GitProxyBinding replacement = new GitProxyBinding(alias, upstream, ref, kind, secret, username, knownHosts);
        if (existing != null && provider.isBootstrapSource(existing, sources)
                && (!existing.upstream().equals(replacement.upstream()) || !existing.ref().equals(replacement.ref()))) {
            throw new Rejected("bootstrap-source-fixed");
        }
        var bindings = new ArrayList<GitProxyBinding>();
        for (GitProxyBinding binding : document.system().proxies()) {
            if (!binding.alias().equals(alias)) {
                if (binding.upstream().equals(replacement.upstream()) && binding.ref().equals(replacement.ref())) {
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
                document.system().accessControl(), document.system().https(), document.system().secrets(), bindings),
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

    private static AliasResponse project(GitProxyBinding binding, SyncObservation observation) {
        return new AliasResponse("system", binding.alias().value(), sanitizedUpstream(binding.upstream()),
                binding.upstream().getScheme(), binding.ref(), "/r/" + binding.publicRepositoryName() + ".git",
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
