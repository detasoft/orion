package pro.deta.orion.agentd;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentLabel;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agentd.core.AgentConfiguration;
import pro.deta.orion.agentd.core.AgentLaunchContext;
import pro.deta.orion.agentd.core.LaunchPermitReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Starts a local AgentD with a fresh server-issued permit obtained over administrative SSH. */
public final class LocalAgentMain {
    private static final String USAGE = """
            Usage: make run-agent [AGENT_ARGS='options']
              --server HTTPS_URI    server control endpoint (https://localhost:8443)
              --state-dir PATH      local state (orion_root/agentd-local)
              --agent-label LABEL   stable server label (local)
              --ssh-port PORT       Orion SSH port (8022)
              --ssh-user USER       SSH administrator (root)
              --ssh-option VALUE    additional ssh -o option; may be repeated
              --help                show this help without requesting a permit
            Enroll your admin key once with make enroll-admin-key.
            The server HTTPS listener must be enabled and trusted by this JVM.
            """;

    private LocalAgentMain() {
    }

    public static void main(String[] arguments) {
        if (Arrays.equals(arguments, new String[]{"--help"})) {
            System.out.print(USAGE);
            return;
        }
        int exit = 1;
        byte[] response = null;
        try {
            Options options = options(arguments);
            response = requestPermit(options.sshCommand());
            try (AgentLaunchContext context = authorization(options.label(), response)) {
                Arrays.fill(response, (byte) 0);
                Path state = options.state();
                Path existingParent = state.getParent();
                while (!Files.exists(existingParent)) {
                    existingParent = existingParent.getParent();
                }
                if (Files.getFileStore(existingParent).supportsFileAttributeView("posix")) {
                    Files.createDirectories(state, PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createDirectories(state);
                }
                AgentConfiguration configuration = AgentConfiguration.parse(new String[]{
                        "--server", options.server().toString(), "--state-dir", state.toString(),
                        "--agent-label", options.label(), "--generation", context.generation().value() + "",
                        "--launch-id", context.launchId().value().toString(), "--agent-version", "dev"});
                System.out.println("Starting AgentD " + options.label() + " -> " + options.server());
                AgentdMain.launch(configuration, context);
            }
            exit = 0;
        } catch (IllegalArgumentException failure) {
            System.err.println(failure.getMessage());
            System.err.print(USAGE);
            exit = 2;
        } catch (IOException failure) {
            System.err.println("Local AgentD launch failed: " + failure.getMessage());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            System.err.println("Local AgentD launch interrupted");
        } catch (RuntimeException failure) {
            System.err.println("Local AgentD launch failed; check server HTTPS and local state permissions");
        } finally {
            if (response != null) {
                Arrays.fill(response, (byte) 0);
            }
        }
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static Options options(String[] arguments) {
        URI server = URI.create("https://localhost:8443");
        Path state = Path.of("orion_root/agentd-local").toAbsolutePath().normalize();
        String label = "local";
        String user = "root";
        int port = 8022;
        List<String> sshOptions = new ArrayList<>();
        for (int index = 0; index < arguments.length; index += 2) {
            if (index + 1 == arguments.length) {
                throw new IllegalArgumentException("Missing local AgentD option value");
            }
            String value = arguments[index + 1];
            switch (arguments[index]) {
                case "--server" -> server = URI.create(value);
                case "--state-dir" -> state = Path.of(value).toAbsolutePath().normalize();
                case "--agent-label" -> label = new AgentLabel(value).value();
                case "--ssh-port" -> port = Integer.parseInt(value);
                case "--ssh-user" -> user = value;
                case "--ssh-option" -> sshOptions.add(value);
                default -> throw new IllegalArgumentException("Unknown local AgentD option");
            }
        }
        if (!"https".equalsIgnoreCase(server.getScheme()) || server.getHost() == null
                || server.getUserInfo() != null || server.getQuery() != null || server.getFragment() != null
                || port < 1 || port > 65535 || user.isBlank() || state.getParent() == null) {
            throw new IllegalArgumentException("Invalid local AgentD options; server must use HTTPS");
        }
        return new Options(server, state, label, user, port, List.copyOf(sshOptions));
    }

    static byte[] requestPermit(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        process.getOutputStream().close();
        process.onExit().orTimeout(30, TimeUnit.SECONDS).exceptionally(failure -> {
            process.destroyForcibly();
            return null;
        });
        byte[] response = null;
        try (var output = process.getInputStream()) {
            response = output.readNBytes(4097);
            if (response.length > 4096) {
                throw new IOException("Launch authorization response is too large");
            }
            if (process.waitFor() != 0) {
                throw new IOException("SSH permit request failed; check the server and make enroll-admin-key");
            }
            byte[] result = response;
            response = null;
            return result;
        } finally {
            if (response != null) {
                Arrays.fill(response, (byte) 0);
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    static AgentLaunchContext authorization(String label, byte[] response) {
        try {
            String[] lines = new String(response, StandardCharsets.US_ASCII).split("\n", -1);
            if (response.length > 4096 || lines.length != 4 || !lines[3].isEmpty()) {
                throw new IllegalArgumentException();
            }
            AgentGeneration generation = new AgentGeneration(Long.parseLong(lines[0]));
            AgentLaunchId launchId = new AgentLaunchId(UUID.fromString(lines[1]));
            AgentLabel agentLabel = new AgentLabel(label);
            try (var input = new ByteArrayInputStream((lines[2] + "\n").getBytes(StandardCharsets.US_ASCII))) {
                return new AgentLaunchContext(agentLabel, generation, launchId, new AgentInstanceId(UUID.randomUUID()),
                        new LaunchPermitReader().read(input));
            }
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid launch authorization response");
        }
    }

    record Options(URI server, Path state, String label, String user, int port, List<String> sshOptions) {
        List<String> sshCommand() {
            List<String> command = new ArrayList<>(List.of("ssh", "-T", "-o", "BatchMode=yes",
                    "-o", "PreferredAuthentications=publickey", "-o", "PasswordAuthentication=no",
                    "-o", "ConnectTimeout=10", "-p", port + "", "-l", user));
            for (String option : sshOptions) {
                command.add("-o");
                command.add(option);
            }
            command.add("localhost");
            command.add("issue-launch-permit " + quote(label) + " " + quote(server.toString())
                    + " " + quote(state.toString()) + " dev");
            return List.copyOf(command);
        }

        private static String quote(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }
}
