package io.github.sagaraggarwal86.jmeter.bpm.ai.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the BPM AI system prompt from the bundled JAR resource.
 *
 * <p>The prompt is always read from {@code /bpm-ai-prompt.txt} packaged inside
 * the plugin JAR. It is not user-editable — the analytical framework is an
 * integral part of the plugin.</p>
 */
public final class BpmPromptLoader {

    static final String RESOURCE_PATH = "/bpm-ai-prompt.txt";
    private static final Logger log = LoggerFactory.getLogger(BpmPromptLoader.class);

    private BpmPromptLoader() {
    }

    /**
     * Loads the system prompt from the bundled JAR resource.
     *
     * @return prompt text, or {@code null} if the resource is missing or empty
     */
    public static String load() {
        try (InputStream in = BpmPromptLoader.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                log.error("load: bundled resource {} not found. Plugin JAR may be corrupt.",
                        RESOURCE_PATH);
                return null;
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                log.error("load: bundled resource {} is empty.", RESOURCE_PATH);
                return null;
            }
            log.debug("load: loaded prompt from bundled JAR resource ({} chars).", content.length());
            return content;
        } catch (IOException e) {
            log.error("load: failed to read {}.", RESOURCE_PATH, e);
            return null;
        }
    }
}
