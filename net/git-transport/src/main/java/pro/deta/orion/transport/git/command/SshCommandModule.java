package pro.deta.orion.transport.git.command;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;
import pro.deta.orion.command.CommandAuditDescriber;
import pro.deta.orion.command.CommandDispatcher;
import pro.deta.orion.command.CommandLineParser;
import pro.deta.orion.command.CommandNavigator;
import pro.deta.orion.command.CommandNode;
import pro.deta.orion.command.CommandRowQuery;
import pro.deta.orion.command.DefaultCommandDispatcher;
import pro.deta.orion.command.audit.AuditingCommandDispatcher;
import pro.deta.orion.command.render.PlainCommandRenderer;
import pro.deta.orion.transport.git.command.read.DefaultOperatorDomainSource;
import pro.deta.orion.transport.git.command.read.DefaultRuntimeMetrics;
import pro.deta.orion.transport.git.command.read.OperatorDomainSource;
import pro.deta.orion.transport.git.command.read.RuntimeMetrics;

@Module
public final class SshCommandModule {
    private SshCommandModule() {}

    @Provides
    @Singleton
    static CommandLineParser commandLineParser() {
        return new CommandLineParser();
    }

    @Provides
    @Singleton
    static PlainCommandRenderer plainCommandRenderer() {
        return new PlainCommandRenderer();
    }

    @Provides
    @Singleton
    static RuntimeMetrics runtimeMetrics(DefaultRuntimeMetrics metrics) {
        return metrics;
    }

    @Provides
    @Singleton
    static OperatorDomainSource operatorDomainSource(DefaultOperatorDomainSource source) {
        return source;
    }

    @Provides
    @Singleton
    static CommandNode commandTree(LegacySshCommandCatalog catalog) {
        return catalog.commandTree();
    }

    @Provides
    @Singleton
    static CommandNavigator commandNavigator(CommandNode commandTree) {
        return new CommandNavigator(commandTree);
    }

    @Provides
    @Singleton
    static CommandRowQuery commandRowQuery() {
        return new CommandRowQuery();
    }

    @Provides
    @Singleton
    static DefaultCommandDispatcher defaultCommandDispatcher(
            CommandLineParser parser,
            CommandNode commandTree,
            CommandRowQuery rowQuery) {
        return new DefaultCommandDispatcher(parser, commandTree, rowQuery);
    }

    @Provides
    @Singleton
    static CommandAuditDescriber commandAuditDescriber(DefaultCommandDispatcher dispatcher) {
        return dispatcher;
    }

    @Provides
    @Singleton
    static CommandDispatcher commandDispatcher(
            DefaultCommandDispatcher dispatcher,
            CommandAuditDescriber describer,
            Slf4jCommandAuditSink auditSink) {
        return new AuditingCommandDispatcher(
                dispatcher,
                describer,
                auditSink,
                System::nanoTime);
    }
}
