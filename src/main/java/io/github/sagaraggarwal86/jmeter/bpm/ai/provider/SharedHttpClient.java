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

    private static final HttpClient INSTANCE = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private SharedHttpClient() { }

    public static HttpClient get() {
        return INSTANCE;
    }
}
