package pro.deta.orion.bootstrap.config.location;

record ConfigurationContent(String sourceName, byte[] content) {
    ConfigurationContent {
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("Configuration source name must not be blank");
        }
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
