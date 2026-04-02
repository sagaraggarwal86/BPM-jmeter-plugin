package io.github.sagaraggarwal86.jmeter.bpm.ai.provider;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Single shared {@link HttpClient} singleton for the AI reporting subsystem.
 *
 * <p>Reusing one client allows connection-pool sharing across report
 * generation and provider pings, avoiding redundant TLS handshakes.</p>
 */
public final class SharedHttpClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final HttpClient INSTANCE;

    static {
        HttpClient client;
        try {
            client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
        } catch (Exception e) {
            // Fallback to default client if builder fails (e.g., restricted environment)
            client = HttpClient.newHttpClient();
        }
        INSTANCE = client;
    }

    private SharedHttpClient() {
    }

    public static HttpClient get() {
        return INSTANCE;
    }
}
