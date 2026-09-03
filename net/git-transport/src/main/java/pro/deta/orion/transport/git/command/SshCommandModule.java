package pro.deta.orion.transport.git.command;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;
import pro.deta.orion.command.CommandAuditDescriber;
import pro.deta.orion.command.CommandDispatcher;
import pro.deta.orion.command.CommandLineParser;
import pro.deta.orion.command.CommandNavigator;
import pro.deta.orion.command.CommandNode;
import pro.deta.orion.command.DefaultCommandDispatcher;
import pro.deta.orion.command.audit.AuditingCommandDispatcher;
import pro.deta.orion.command.render.PlainCommandRenderer;

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
    static DefaultCommandDispatcher defaultCommandDispatcher(
            CommandLineParser parser,
            CommandNode commandTree) {
        return new DefaultCommandDispatcher(parser, commandTree);
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
