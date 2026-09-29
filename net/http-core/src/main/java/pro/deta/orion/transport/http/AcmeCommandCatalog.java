package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import pro.deta.orion.util.LogScope;
import pro.deta.orion.acl.storage.AccessControlConcurrentUpdateException;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.auth.check.resource.ApplicationAdminResource;
import pro.deta.orion.auth.check.rule.ApplicationAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.command.CommandCompletion;
import pro.deta.orion.command.CommandDefinition;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandHandler;
import pro.deta.orion.command.CommandInvocation;
import pro.deta.orion.command.CommandNode;
import pro.deta.orion.command.CommandQuery;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandValue;

import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** SSH entry points use the same settings and issuance owners as the admin HTTP routes. */
public final class AcmeCommandCatalog {
    private final AcmeConfigurationService configuration;
    private final AcmeCertificateService certificates;

    @Inject
    public AcmeCommandCatalog(AcmeConfigurationService configuration, AcmeCertificateService certificates) {
        this.configuration = configuration;
        this.certificates = certificates;
    }

    public CommandNode commandTree() {
        return CommandNode.builder()
                .action(definition("show", Set.of(), Set.of(), ignored -> view(configuration.view())))
                .action(definition("configure", Set.of("revision", "provider", "directory-url", "email", "domains",
                        "eab-kid", "eab-hmac-key"), Set.of("eab-hmac-key"), this::configure))
                .action(definition("issue", Set.of(), Set.of(), ignored -> {
                    certificates.issue(new AcmeCertificateService.IssueRequest(
                            null, null, null, null, null, null, true));
                    return new CommandResult.Message("Certificate issued and saved.");
                }))
                .action(definition("help", Set.of(), Set.of(), ignored -> new CommandResult.Message("""
                        /acme show
                        /acme configure revision=<revision> provider=<letsencrypt|zerossl|google|custom> \
                        email=<email> domains=<domain,domain> [directory-url=<url>] \
                        [eab-kid=<id> eab-hmac-key=<base64url-key>]
                        /acme issue
                        EAB credentials are stored encrypted. Omit the HMAC key to keep saved credentials.
                        Issuing confirms acceptance of the selected provider's terms of service.
                        Domain verification uses HTTP-01 on port 80.
                        /acme show includes automatic renewal status; keep HTTP-01 reachable for renewals.
                        """)))
                .build();
    }

    private CommandResult configure(CommandInvocation invocation) {
        Map<String, String> parameters = invocation.arguments().named();
        char[] key = parameters.getOrDefault("eab-hmac-key", "").toCharArray();
        try {
            return view(configuration.save(new AcmeConfigurationService.Settings(parameters.get("revision"),
                    parameters.get("provider"), parameters.get("directory-url"), parameters.get("email"),
                    Arrays.asList(parameters.getOrDefault("domains", "").split(",", -1)),
                    parameters.get("eab-kid"), key), invocation.context().securityContext()
                    .getUserIdentity().getUserId()));
        } finally {
            Arrays.fill(key, '\0');
        }
    }

    private static CommandDefinition definition(String action, Set<String> parameters, Set<String> sensitive,
            CommandHandler handler) {
        return new CommandDefinition(action, 0, 0, parameters, sensitive,
                context -> admin(context.securityContext()).allowed(),
                invocation -> admin(invocation.context().securityContext()), invocation -> {
                    try (LogScope ignored = LogScope.user(invocation.context().securityContext()
                            .getUserIdentity().getUserId())) {
                        return handler.handle(invocation);
                    } catch (AccessControlConcurrentUpdateException conflict) {
                        return failure("Configuration changed. Run /acme show and retry with its revision.");
                    } catch (AcmeCertificateService.IssuanceBusyException busy) {
                        return failure("Certificate issuance is already in progress.");
                    } catch (IllegalArgumentException invalid) {
                        return new CommandResult.Failure(CommandFailureCode.INVALID_ARGUMENTS,
                                "Check ACME settings. New providers/accounts require EAB credentials.", List.of());
                    } catch (Exception failed) {
                        return failure("ACME operation failed. Check configuration and CA availability.");
                    }
                }, CommandCompletion.none(), CommandQuery.none());
    }

    private static AccessDecision admin(SecurityContext context) {
        AccessDecision authenticated = SubjectAccessRules.authenticated().evaluate(context);
        return authenticated.allowed()
                ? ApplicationAccessRules.admin().evaluate(context, ApplicationAdminResource.applicationAdmin())
                : authenticated;
    }

    private static CommandResult failure(String message) {
        return new CommandResult.Failure(CommandFailureCode.HANDLER_FAILED, message, List.of());
    }

    private static CommandResult view(AcmeConfigurationService.View view) {
        Map<String, CommandValue> fields = new LinkedHashMap<>();
        fields.put("revision", CommandValue.text(view.revision()));
        fields.put("enabled", CommandValue.bool(view.enabled()));
        fields.put("provider", CommandValue.text(view.provider()));
        fields.put("directoryUrl", CommandValue.text(view.directoryUrl()));
        fields.put("accountEmail", CommandValue.text(view.accountEmail()));
        fields.put("domains", CommandValue.text(String.join(",", view.domains())));
        fields.put("eabKeyId", CommandValue.text(view.eabKeyId()));
        fields.put("eabConfigured", CommandValue.bool(view.eabConfigured()));
        fields.put("renewalState", CommandValue.text(view.renewal().state()));
        fields.put("expiresAt", CommandValue.text(view.renewal().expiresAt()));
        fields.put("nextAttempt", CommandValue.text(view.renewal().nextAttempt()));
        fields.put("lastAttempt", CommandValue.text(view.renewal().lastAttempt()));
        fields.put("lastSuccess", CommandValue.text(view.renewal().lastSuccess()));
        fields.put("renewalMessage", CommandValue.text(view.renewal().message()));
        fields.put("activationError", CommandValue.text(view.renewal().activationError()));
        return new CommandResult.ObjectValue(fields);
    }
}
