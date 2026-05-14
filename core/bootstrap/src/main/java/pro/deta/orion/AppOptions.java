package pro.deta.orion;

import java.util.Objects;

record AppOptions(String configurationLocation, boolean helpRequested) {
    static AppOptions parse(String[] args) {
        Objects.requireNonNull(args, "args");
        String configurationLocation = null;
        boolean helpRequested = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> helpRequested = true;
                case "--config", "-c" -> {
                    i++;
                    if (i >= args.length) {
                        throw new IllegalArgumentException(arg + " requires a value");
                    }
                    configurationLocation = setConfigurationLocation(configurationLocation, args[i]);
                }
                default -> {
                    if (arg.startsWith("--config=")) {
                        configurationLocation = setConfigurationLocation(
                                configurationLocation,
                                arg.substring("--config=".length()));
                    } else {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                }
            }
        }

        return new AppOptions(configurationLocation, helpRequested);
    }

    static String usage() {
        return """
                Usage: orion [options]

                Options:
                  -c, --config <location>  Read configuration from a file path or classpath:// resource.
                  -h, --help               Show this help.
                """;
    }

    private static String setConfigurationLocation(String currentValue, String nextValue) {
        if (currentValue != null) {
            throw new IllegalArgumentException("Configuration location is already specified");
        }
        if (nextValue == null || nextValue.isBlank()) {
            throw new IllegalArgumentException("Configuration location must not be blank");
        }
        return nextValue;
    }
}
