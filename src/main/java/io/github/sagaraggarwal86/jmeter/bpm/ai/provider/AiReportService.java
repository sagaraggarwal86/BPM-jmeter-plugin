package io.github.sagaraggarwal86.jmeter.bpm.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sagaraggarwal86.jmeter.bpm.ai.prompt.BpmPromptContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Calls an OpenAI-compatible AI API and returns the generated report in Markdown format.
 *
 * <p>Retry behaviour: up to 3 attempts with a 2-second delay between attempts.
 * Only HTTP 429 and 5xx responses are retried.</p>
 */
public class AiReportService {

    static final String SECTION_SKELETON = "## Executive Summary\n\n";
    private static final Logger log = LoggerFactory.getLogger(AiReportService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 2_000L;
    private static final String[] EXPECTED_SECTION_HEADINGS = {
            "## Executive Summary",
            "## Risk Assessment",
    };

    private static final String TRUNCATION_WITH_MISSING_NOTICE_TEMPLATE =
            "\n\n> **Warning: Partial report — %s reached its output limit**\n"
                    + ">\n"
                    + "> **Sections completed:** %s\n"
                    + ">\n"
                    + "> **Sections not reached:** %s\n"
                    + ">\n"
                    + "> To get a complete report, try regenerating or "
                    + "increase `max.tokens` for this provider in `ai-reporter.properties`.";

    private static final String TRUNCATION_NOTICE_TEMPLATE =
            "\n\n> **Warning: Report truncated** — The %s response was cut off. "
                    + "All section headings are present but the final section may be incomplete. "
                    + "Try increasing `max.tokens` in `ai-reporter.properties`.";

    private static final String MISSING_SECTIONS_NOTICE_TEMPLATE =
            "\n\n> **Warning: Missing sections** — The following section(s) were not generated "
                    + "by %s: **%s**. Try regenerating the report or use a different provider.";

    final AiProviderConfig config;

    public AiReportService(AiProviderConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    private static String sanitizeBody(String body) {
        if (body == null || body.isBlank()) return "(empty)";
        String stripped = body.replaceAll("<[^>]+>", "").trim();
        return stripped.length() > 200 ? stripped.substring(0, 200) + "..." : stripped;
    }

    public String generateReport(BpmPromptContent promptContent) throws IOException {
        Objects.requireNonNull(promptContent, "promptContent must not be null");
        log.info("generateReport: provider={} model={} systemLength={} userLength={}",
                config.providerKey, config.model,
                promptContent.systemPrompt().length(), promptContent.userMessage().length());
        long start = System.currentTimeMillis();
        String result = callProvider(promptContent);
        log.info("generateReport: completed. provider={} elapsed={}ms responseChars={}",
                config.providerKey, System.currentTimeMillis() - start, result.length());
        return result;
    }

    private String callProvider(BpmPromptContent content) throws IOException {
        String requestBody = buildRequestBody(content);
        AiServiceException lastEx = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpResponse<String> response = sendRequest(requestBody);
            int status = response.statusCode();

            if (status == 200) {
                return extractAndValidateContent(response.body());
            }

            boolean retryable = (status == 429 || (status >= 500 && status < 600));
            String statusHint = switch (status) {
                case 400 -> " Check the model ID and request format.";
                case 401 -> " API key is invalid — check ai-reporter.properties.";
                case 403 -> " Access denied — check API quota or billing status.";
                case 404 -> " Model not found — check ai.reporter." + config.providerKey + ".model.";
                case 429 -> " Rate limit exceeded — wait or upgrade your plan.";
                default -> status >= 500 ? " Provider server error — retry later." : "";
            };
            lastEx = new AiServiceException(String.format(
                    "%s API returned HTTP %d.%s Body: %s",
                    config.displayName, status, statusHint, sanitizeBody(response.body())));

            if (!retryable || attempt == MAX_ATTEMPTS) throw lastEx;

            log.warn("callProvider: attempt {}/{} failed with HTTP {} for provider={}. Retrying.",
                    attempt, MAX_ATTEMPTS, status, config.providerKey);
            sleepBeforeRetry();
        }
        throw Objects.requireNonNull(lastEx);
    }

    private HttpResponse<String> sendRequest(String requestBody) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.chatCompletionsUrl()))
                .timeout(Duration.ofSeconds(config.timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        try {
            return SharedHttpClient.get().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiServiceException(
                    config.displayName + " API request was interrupted. Please try again.", e);
        }
    }

    private void sleepBeforeRetry() throws AiServiceException {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiServiceException(config.displayName + " retry sleep interrupted", e);
        }
    }

    private String buildRequestBody(BpmPromptContent content) {
        ObjectNode systemMsg = mapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", content.systemPrompt());

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", content.userMessage());

        ArrayNode messages = mapper.createArrayNode();
        messages.add(systemMsg);
        messages.add(userMsg);

        // Assistant prefill — skip for Cerebras (causes early-stop)
        if (!"cerebras".equals(config.providerKey)) {
            ObjectNode prefillMsg = mapper.createObjectNode();
            prefillMsg.put("role", "assistant");
            prefillMsg.put("content", SECTION_SKELETON);
            prefillMsg.put("prefix", true);
            messages.add(prefillMsg);
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.model);
        body.put("temperature", config.temperature);
        body.put("max_tokens", config.maxTokens);
        body.set("messages", messages);

        return body.toString();
    }

    String extractAndValidateContent(String responseBody) throws AiServiceException {
        try {
            JsonNode root = mapper.readTree(responseBody);

            JsonNode choices = root.get("choices");
            if (choices == null || choices.isNull() || !choices.isArray() || choices.isEmpty()) {
                throw new AiServiceException("Failed to parse " + config.displayName
                        + " API response: choices field missing or empty.");
            }

            JsonNode choice = choices.get(0);
            String aiText = choice.path("message").path("content").asText(null);

            if (aiText == null || aiText.isBlank()) {
                throw new AiServiceException(config.displayName + " API returned an empty response.");
            }

            // Prepend skeleton heading if not already present
            String fullMarkdown = aiText.startsWith(SECTION_SKELETON.trim())
                    ? aiText
                    : SECTION_SKELETON + aiText;

            // Classify present / missing sections
            List<String> presentSections = new ArrayList<>();
            List<String> missingSections = new ArrayList<>();
            for (String heading : EXPECTED_SECTION_HEADINGS) {
                String name = heading.replace("## ", "");
                if (fullMarkdown.contains("\n" + heading) || fullMarkdown.startsWith(heading)) {
                    presentSections.add(name);
                } else {
                    missingSections.add(name);
                }
            }

            // Truncation detection
            boolean truncated = false;
            String finishReason = choice.path("finish_reason").asText("");
            if ("length".equals(finishReason)) truncated = true;

            if (!truncated && root.has("usage")) {
                int completionTokens = root.path("usage").path("completion_tokens").asInt(0);
                if (completionTokens >= config.maxTokens) truncated = true;
            }

            // Compose notice
            if (truncated && !missingSections.isEmpty()) {
                String present = String.join(", ", presentSections);
                String missing = String.join(", ", missingSections);
                return fullMarkdown + String.format(TRUNCATION_WITH_MISSING_NOTICE_TEMPLATE,
                        config.displayName, present, missing);
            }
            if (truncated) {
                return fullMarkdown + String.format(TRUNCATION_NOTICE_TEMPLATE, config.displayName);
            }
            if (!missingSections.isEmpty()) {
                String missingList = String.join(", ", missingSections);
                return fullMarkdown + String.format(MISSING_SECTIONS_NOTICE_TEMPLATE,
                        config.displayName, missingList);
            }
            return fullMarkdown;

        } catch (AiServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new AiServiceException("Failed to parse " + config.displayName
                    + " API response: " + e.getMessage(), e);
        }
    }
}
