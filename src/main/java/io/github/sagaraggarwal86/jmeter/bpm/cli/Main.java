package io.github.sagaraggarwal86.jmeter.bpm.cli;

import io.github.sagaraggarwal86.jmeter.bpm.ai.provider.AiServiceException;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Command-line entry point for BPM AI report generation.
 *
 * <p>Generates an AI-powered browser performance analysis report from a
 * BPM JSONL results file without requiring JMeter GUI.</p>
 *
 * <h3>Exit codes</h3>
 * <ul>
 *   <li>{@code 0} — Success: report generated</li>
 *   <li>{@code 1} — Invalid command-line arguments</li>
 *   <li>{@code 2} — JSONL parse error</li>
 *   <li>{@code 3} — AI provider error (invalid key, ping failed, API error)</li>
 *   <li>{@code 4} — Report file write error</li>
 *   <li>{@code 5} — Unexpected error</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * java -cp "lib/ext/*:lib/*" io.github.sagaraggarwal86.jmeter.bpm.cli.Main \
 *   -i bpm-results.jsonl --provider groq --config ai-reporter.properties
 * }</pre>
 */
public final class Main {

    static final int EXIT_SUCCESS = 0;
    static final int EXIT_BAD_ARGS = 1;
    static final int EXIT_PARSE_ERROR = 2;
    static final int EXIT_AI_ERROR = 3;
    static final int EXIT_WRITE_ERROR = 4;
    static final int EXIT_UNEXPECTED = 5;

    private Main() { }

    public static void main(String[] args) {
        // 1. Parse arguments
        CliArgs cli = CliArgs.parse(args);

        // 2. Handle help
        if (cli.help()) {
            CliArgs.printHelp();
            System.exit(EXIT_SUCCESS);
            return;
        }

        // 3. Check for argument errors
        if (!cli.errors().isEmpty()) {
            System.err.println("ERROR: Invalid arguments:");
            cli.errors().forEach(e -> System.err.println("  - " + e));
            System.err.println();
            System.err.println("Run with --help for usage information.");
            System.exit(EXIT_BAD_ARGS);
            return;
        }

        // 4. Execute pipeline
        try {
            Path reportPath = BpmCliReportPipeline.execute(cli);
            // Print report path to stdout (machine-parseable)
            System.out.println(reportPath.toAbsolutePath());
            System.exit(EXIT_SUCCESS);

        } catch (AiServiceException e) {
            System.err.println("ERROR: AI provider failure: " + e.getMessage());
            System.exit(EXIT_AI_ERROR);
        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("No valid BPM results")) {
                System.err.println("ERROR: " + msg);
                System.exit(EXIT_PARSE_ERROR);
            } else {
                System.err.println("ERROR: " + msg);
                System.exit(EXIT_WRITE_ERROR);
            }
        } catch (Exception e) {
            System.err.println("ERROR: Unexpected failure: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(EXIT_UNEXPECTED);
        }
    }
}
