package io.github.sagaraggarwal86.jmeter.bpm.collectors;

import io.github.sagaraggarwal86.jmeter.bpm.core.CdpCommandExecutor;
import io.github.sagaraggarwal86.jmeter.bpm.core.MetricsBuffer;
import io.github.sagaraggarwal86.jmeter.bpm.model.NetworkResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.ResourceEntry;
import io.github.sagaraggarwal86.jmeter.bpm.util.TypeConverters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Collects network metrics from the {@link MetricsBuffer}.
 *
 * <p>Drains all buffered network responses (populated by
 * {@code CdpSessionManager.transferBufferedEvents()}) and computes:</p>
 * <ul>
 *   <li>Total request count</li>
 *   <li>Total bytes transferred</li>
 *   <li>Number of failed requests (HTTP 4xx/5xx)</li>
 *   <li>Top N slowest successful resources (sorted by duration descending)</li>
 *   <li>All failed requests (regardless of speed)</li>
 * </ul>
 *
 * <p>The {@code slowest} list in the result contains the top N slowest
 * <em>plus</em> all failed requests, deduplicated. Default N = 5,
 * configurable via {@code network.topN} property.</p>
 */
public final class NetworkCollector implements MetricsCollector<NetworkResult> {

    private static final Logger log = LoggerFactory.getLogger(NetworkCollector.class);

    private final int topN;

    /**
     * Creates a network collector with the given top-N configuration.
     *
     * @param topN number of slowest resources to include (from {@code BpmPropertiesManager.getNetworkTopN()})
     */
    public NetworkCollector(int topN) {
        this.topN = Math.max(1, topN);
    }

    /**
     * Determines if an HTTP status code indicates failure (4xx or 5xx).
     * Status 0 means unknown (Resource Timing API limitation) — treated as success.
     */
    private static boolean isFailed(int status) {
        return status >= 400;
    }

    /**
     * Truncates URL to 512 characters to limit payload size.
     */
    private static String truncateUrl(String url) {
        return url.length() > 512 ? url.substring(0, 512) : url;
    }

    /**
     * Drains the network response buffer and computes network metrics.
     *
     * @param executor the CDP command executor (not used directly; network data
     *                 comes from the buffer populated during event transfer)
     * @param buffer   the metrics buffer to drain
     * @return the network metrics result; never null
     */
    @Override
    public NetworkResult collect(CdpCommandExecutor executor, MetricsBuffer buffer) {
        List<Map<String, Object>> responses = buffer.drainNetworkResponses();

        if (responses.isEmpty()) {
            return new NetworkResult(0, 0L, 0, List.of());
        }

        int totalRequests = responses.size();
        long totalBytes = 0;
        int failedRequests = 0;
        List<ResourceEntry> allEntries = new ArrayList<>(responses.size());
        Set<ResourceEntry> failedEntries = new LinkedHashSet<>();

        for (Map<String, Object> resp : responses) {
            String url = truncateUrl(String.valueOf(resp.getOrDefault("url", "")));
            long duration = TypeConverters.toLong(resp.get("duration"));
            long size = TypeConverters.toLong(resp.get("transferSize"));
            long ttfb = TypeConverters.toLong(resp.get("ttfb"));
            int status = TypeConverters.toInt(resp.get("status"));

            totalBytes += size;

            ResourceEntry entry = new ResourceEntry(url, duration, size, ttfb);

            if (isFailed(status)) {
                failedRequests++;
                failedEntries.add(entry);
            }

            allEntries.add(entry);
        }

        // Sort all entries by duration descending for top-N selection
        allEntries.sort(Comparator.comparingLong(ResourceEntry::duration).reversed());

        // Build slowest list: top N successful + all failed, deduplicated
        Set<ResourceEntry> slowestSet = new LinkedHashSet<>();
        int added = 0;
        for (ResourceEntry entry : allEntries) {
            if (added >= topN) {
                break;
            }
            if (failedEntries.contains(entry)) {
                continue;
            }
            slowestSet.add(entry);
            added++;
        }
        // Add all failed regardless of whether they were already in top N
        slowestSet.addAll(failedEntries);

        return new NetworkResult(totalRequests, totalBytes, failedRequests, List.copyOf(slowestSet));
    }
}