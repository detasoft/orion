package pro.deta.orion.git.parser.v2.command;

import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;

import java.io.IOException;

public interface GitCommand {
    void action(GitProtocolContext protocolContext) throws IOException;
}
