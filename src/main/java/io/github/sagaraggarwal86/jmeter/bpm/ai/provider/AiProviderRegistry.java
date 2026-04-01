package io.github.sagaraggarwal86.jmeter.bpm.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code ai-reporter.properties}, discovers any provider whose
 * {@code api.key} is non-blank, resolves per-provider defaults, and exposes
 * the results as an ordered list of {@link AiProviderConfig} instances.
 *
 * <p>Shares the same properties file as the JAAR plugin — users configure
 * API keys once for both plugins.</p>
 */
public final class AiProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiProviderRegistry.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    static final List<String> KNOWN_PROVIDERS = List.of(
            "groq", "gemini", "mistral", "deepseek", "cerebras", "openai", "claude"
    );

    private static final String PREFIX = "ai.reporter.";
    private static final Pattern API_KEY_PATTERN =
            Pattern.compile("^ai\\.reporter\\.([^.]+)\\.api\\.key$");

    private static final int DEFAULT_TIMEOUT = 60;
    private static final int DEFAULT_MAX_TOKENS = 8192;
    private static final double DEFAULT_TEMPERATURE = 0.3;

    private static final Map<String, String> KNOWN_LABELS = Map.of(
            "groq", "Groq (Free)",
            "gemini", "Gemini (Free)",
            "mistral", "Mistral (Free)",
            "deepseek", "DeepSeek (Free)",
            "cerebras", "Cerebras (Free)",
            "openai", "OpenAI (Paid)",
            "claude", "Claude (Paid)"
    );

    private static final Map<String, String> KNOWN_DEFAULT_MODELS = Map.of(
            "groq", "llama-3.3-70b-versatile",
            "gemini", "gemini-1.5-pro",
            "mistral", "mistral-large-latest",
            "deepseek", "deepseek-chat",
            "cerebras", "qwen-3-235b-a22b-instruct-2507",
            "openai", "gpt-4o",
            "claude", "claude-sonnet-4-6"
    );

    private static final Map<String, String> KNOWN_BASE_URLS = Map.of(
            "groq", "https://api.groq.com/openai/v1",
            "gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
            "mistral", "https://api.mistral.ai/v1",
            "deepseek", "https://api.deepseek.com/v1",
            "cerebras", "https://api.cerebras.ai/v1",
            "openai", "https://api.openai.com/v1",
            "claude", "https://api.anthropic.com/v1"
    );

    private static final Map<String, String> KNOWN_KEY_PREFIXES = Map.of(
            "groq", "gsk_",
            "openai", "sk-",
            "claude", "sk-ant-",
            "gemini", "AIza",
            "cerebras", "csk-"
    );

    private static final ConcurrentHashMap<String, Boolean> PING_CACHE = new ConcurrentHashMap<>();

    private AiProviderRegistry() { }

    // ── Public API ────────────────────────────────────────────────────────────

    public static List<AiProviderConfig> loadConfiguredProviders(File jmeterHome) {
        Properties props = loadProperties(jmeterHome);
        return buildProviderList(props);
    }

    /**
     * Loads configured providers from an explicit properties file path.
     * Used by the CLI module when {@code --config} is specified.
     *
     * @param configFile path to the {@code ai-reporter.properties} file
     * @return ordered, possibly empty, list of configured providers
     * @throws IOException if the file cannot be read
     */
    public static List<AiProviderConfig> loadConfiguredProviders(java.nio.file.Path configFile)
            throws IOException {
        Properties props = new Properties();
        try (InputStream in = java.nio.file.Files.newInputStream(configFile)) {
            props.load(in);
        }
        return buildProviderList(props);
    }

    public static String validateAndPing(AiProviderConfig config) {
        String formatError = checkKeyFormat(config);
        if (formatError != null) return formatError;

        if (Boolean.TRUE.equals(PING_CACHE.get(cacheKey(config)))) {
            log.debug("validateAndPing: ping cache hit for provider={}", config.providerKey);
            return null;
        }

        return executePing(config);
    }

    public static void evictPingCache(AiProviderConfig config) {
        PING_CACHE.remove(cacheKey(config));
    }

    // ── Properties loading ────────────────────────────────────────────────────

    static Properties loadProperties(File jmeterHome) {
        if (jmeterHome != null) {
            File propsFile = new File(jmeterHome, "bin/ai-reporter.properties");
            if (propsFile.isFile()) {
                try (InputStream in = new FileInputStream(propsFile)) {
                    Properties p = new Properties();
                    p.load(in);
                    log.debug("loadProperties: loaded from disk: {}", propsFile.getAbsolutePath());
                    return p;
                } catch (IOException e) {
                    log.warn("loadProperties: failed to read {}. Falling back to JAR resource.",
                            propsFile.getAbsolutePath());
                }
            }
        }
        return loadFromResource();
    }

    private static Properties loadFromResource() {
        try (InputStream in = AiProviderRegistry.class.getResourceAsStream("/ai-reporter.properties")) {
            if (in == null) {
                log.warn("loadFromResource: /ai-reporter.properties not found. Returning empty properties.");
                return new Properties();
            }
            Properties p = new Properties();
            p.load(in);
            return p;
        } catch (IOException e) {
            log.error("loadFromResource: failed to load JAR resource. reason={}", e.getMessage());
            return new Properties();
        }
    }

    // ── Provider list construction ────────────────────────────────────────────

    private static List<AiProviderConfig> buildProviderList(Properties props) {
        Set<String> allConfigured = new LinkedHashSet<>();
        for (String name : props.stringPropertyNames()) {
            Matcher m = API_KEY_PATTERN.matcher(name);
            if (m.matches()) {
                String key = m.group(1);
                String apiKey = props.getProperty(name, "").trim();
                if (!apiKey.isEmpty()) {
                    allConfigured.add(key);
                }
            }
        }

        List<String> canonical = resolveOrder(props);
        List<String> ordered = new ArrayList<>();
        for (String key : canonical) {
            if (allConfigured.contains(key)) ordered.add(key);
        }
        allConfigured.stream()
                .filter(k -> !canonical.contains(k))
                .sorted()
                .forEach(ordered::add);

        List<AiProviderConfig> result = new ArrayList<>();
        for (String key : ordered) {
            AiProviderConfig cfg = buildConfig(key, props);
            if (cfg != null) result.add(cfg);
        }
        log.debug("buildProviderList: {} configured provider(s): {}", result.size(), ordered);
        return result;
    }

    private static List<String> resolveOrder(Properties props) {
        String orderProp = props.getProperty(PREFIX + "order", "").trim();
        if (!orderProp.isBlank()) {
            List<String> userOrder = new ArrayList<>();
            for (String part : orderProp.split(",")) {
                String key = part.trim().toLowerCase(Locale.ROOT);
                if (!key.isEmpty() && !userOrder.contains(key)) {
                    userOrder.add(key);
                }
            }
            if (!userOrder.isEmpty()) return userOrder;
        }
        return KNOWN_PROVIDERS;
    }

    private static AiProviderConfig buildConfig(String key, Properties props) {
        String apiKey = resolve(props, key, "api.key", "");
        String model = resolve(props, key, "model", KNOWN_DEFAULT_MODELS.getOrDefault(key, ""));
        String baseUrl = resolve(props, key, "base.url", KNOWN_BASE_URLS.getOrDefault(key, ""));
        int timeout = resolveInt(props, key, "timeout.seconds", DEFAULT_TIMEOUT);
        int maxTok = resolveInt(props, key, "max.tokens", DEFAULT_MAX_TOKENS);
        double temp = resolveDouble(props, key, "temperature", DEFAULT_TEMPERATURE);

        if (model.isEmpty() || baseUrl.isEmpty()) {
            log.warn("buildConfig: provider '{}' skipped — model or base.url not set.", key);
            return null;
        }

        String label = resolveLabel(key, props);
        try {
            return new AiProviderConfig(key, label, apiKey, model, baseUrl, timeout, maxTok, temp);
        } catch (IllegalArgumentException e) {
            log.warn("buildConfig: provider '{}' skipped — {}.", key, e.getMessage());
            return null;
        }
    }

    private static String resolveLabel(String key, Properties props) {
        String tier = resolve(props, key, "tier", "").trim();
        if (!tier.isBlank()) {
            String base = Character.toUpperCase(key.charAt(0)) + key.substring(1);
            return base + " (" + tier + ")";
        }
        return KNOWN_LABELS.getOrDefault(key,
                Character.toUpperCase(key.charAt(0)) + key.substring(1));
    }

    private static String resolve(Properties props, String providerKey,
                                  String field, String defaultValue) {
        String v = props.getProperty(PREFIX + providerKey + "." + field, "").trim();
        return v.isEmpty() ? defaultValue : v;
    }

    private static int resolveInt(Properties props, String providerKey,
                                  String field, int defaultValue) {
        String v = props.getProperty(PREFIX + providerKey + "." + field, "").trim();
        if (v.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double resolveDouble(Properties props, String providerKey,
                                        String field, double defaultValue) {
        String v = props.getProperty(PREFIX + providerKey + "." + field, "").trim();
        if (v.isEmpty()) return defaultValue;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ── Structural validation ─────────────────────────────────────────────────

    static String checkKeyFormat(AiProviderConfig config) {
        String expectedPrefix = KNOWN_KEY_PREFIXES.get(config.providerKey);
        if (expectedPrefix == null) return null;
        if (!config.apiKey.startsWith(expectedPrefix)) {
            return "The " + config.displayName + " API key should start with \""
                    + expectedPrefix + "\".\n\n"
                    + "Please check the value of:\n"
                    + "  ai.reporter." + config.providerKey + ".api.key\n"
                    + "in ai-reporter.properties.";
        }
        return null;
    }

    private static String cacheKey(AiProviderConfig config) {
        return config.providerKey + ":" + config.apiKey;
    }

    // ── Live ping ─────────────────────────────────────────────────────────────

    private static String executePing(AiProviderConfig config) {
        String url = config.chatCompletionsUrl();
        String body = buildPingBody(config);
        log.debug("executePing: pinging provider={} url={}", config.providerKey, url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            HttpResponse<String> response =
                    SharedHttpClient.get().send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status >= 200 && status < 300) {
                PING_CACHE.put(cacheKey(config), Boolean.TRUE);
                log.info("executePing: provider={} status={} — OK", config.providerKey, status);
                return null;
            }
            PING_CACHE.remove(cacheKey(config));
            return buildPingErrorMessage(config, status, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Connection to " + config.displayName + " was interrupted. Please try again.";
        } catch (IOException e) {
            PING_CACHE.remove(cacheKey(config));
            return "Could not connect to " + config.displayName + ".\n\n"
                    + "Please check your network connection and that the base URL is correct:\n"
                    + "  " + config.baseUrl;
        }
    }

    private static String buildPingBody(AiProviderConfig config) {
        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", "hi");

        ArrayNode messages = mapper.createArrayNode();
        messages.add(userMsg);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.model);
        body.put("max_tokens", 1);
        body.set("messages", messages);

        return body.toString();
    }

    private static String buildPingErrorMessage(AiProviderConfig config, int status, String responseBody) {
        return switch (status) {
            case 401 -> "The " + config.displayName + " API key was rejected (HTTP 401).\n\n"
                    + "Please update ai.reporter." + config.providerKey + ".api.key in ai-reporter.properties.";
            case 403 -> "Access denied by " + config.displayName + " (HTTP 403).\n\n"
                    + "Your account may lack permissions or have exceeded its quota.";
            case 404 -> "Endpoint not found for " + config.displayName + " (HTTP 404).\n\n"
                    + "Please verify ai.reporter." + config.providerKey + ".base.url in ai-reporter.properties.\n"
                    + "Current URL: " + config.chatCompletionsUrl();
            case 429 -> "Rate limit exceeded for " + config.displayName + " (HTTP 429). Please wait and try again.";
            default -> "Unexpected response from " + config.displayName + " (HTTP " + status + ").";
        };
    }
}
