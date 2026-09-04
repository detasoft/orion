package pro.deta.orion.transport.git.command.read;

import java.util.List;

public interface OperatorDomainSource {
    OperatorQueryResult<List<OperatorDomainViews.RepositoryView>> repositories();

    OperatorQueryResult<List<OperatorDomainViews.OrganizationView>> organizations();

    OperatorQueryResult<List<OperatorDomainViews.UserView>> organizationUsers(String organizationId);

    OperatorQueryResult<List<OperatorDomainViews.RepositoryView>> organizationRepositories(String organizationId);

    OperatorQueryResult<List<OperatorDomainViews.SessionView>> sessions();

    OperatorQueryResult<List<OperatorDomainViews.ProxyView>> proxies();

    OperatorQueryResult<OperatorDomainViews.SystemResourceView> systemResources();

    OperatorQueryResult<List<OperatorDomainViews.ServiceView>> services();
}
