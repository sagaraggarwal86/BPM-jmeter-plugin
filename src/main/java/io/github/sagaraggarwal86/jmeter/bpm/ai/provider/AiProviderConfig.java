package io.github.sagaraggarwal86.jmeter.bpm.ai.provider;

/**
 * Immutable value object holding the fully-resolved configuration for one AI provider.
 *
 * <p>Instances are created by {@link AiProviderRegistry} after reading and merging
 * {@code ai-reporter.properties} with built-in provider defaults. All fields are
 * guaranteed non-null and non-blank when produced by the registry.</p>
 */
public final class AiProviderConfig {

    public final String providerKey;
    public final String displayName;
    public final String apiKey;
    public final String model;
    public final String baseUrl;
    public final int timeoutSeconds;
    public final int maxTokens;
    public final double temperature;

    public AiProviderConfig(String providerKey, String displayName, String apiKey,
                            String model, String baseUrl,
                            int timeoutSeconds, int maxTokens, double temperature) {
        if (providerKey == null || providerKey.isBlank())
            throw new IllegalArgumentException("providerKey must not be blank");
        if (displayName == null || displayName.isBlank())
            throw new IllegalArgumentException("displayName must not be blank");
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalArgumentException("apiKey must not be blank");
        if (model == null || model.isBlank())
            throw new IllegalArgumentException("model must not be blank");
        if (baseUrl == null || baseUrl.isBlank())
            throw new IllegalArgumentException("baseUrl must not be blank");
        if (timeoutSeconds <= 0)
            throw new IllegalArgumentException("timeoutSeconds must be > 0");
        if (maxTokens <= 0)
            throw new IllegalArgumentException("maxTokens must be > 0");

        this.providerKey = providerKey;
        this.displayName = displayName;
        this.apiKey = apiKey;
        this.model = model.trim();
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.timeoutSeconds = timeoutSeconds;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
    }

    public String chatCompletionsUrl() {
        return baseUrl + "/chat/completions";
    }

    @Override
    public String toString() {
        return displayName;
    }
}
