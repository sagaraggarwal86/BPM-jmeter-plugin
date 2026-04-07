package io.github.sagaraggarwal86.jmeter.bpm.cli;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Command-line entry point for the BPM Report Generator.
 *
 * <p>Generates a browser performance analysis report from a
 * BPM JSONL results file produced by JMeter with the BPM plugin.</p>
 *
 * <h2>Workflow</h2>
 * <pre>{@code
 * # Step 1: Run JMeter test (standard non-GUI mode)
 * jmeter -n -t test.jmx -l results.jtl -Jbpm.output=bpm-results.jsonl
 *
 * # Step 2: Generate report
 * bpm-report -i bpm-results.jsonl
 * }</pre>
 *
 * <h2>Exit codes</h2>
 * <ul>
 *   <li>{@code 0} — Success: report generated</li>
 *   <li>{@code 1} — Invalid command-line arguments</li>
 *   <li>{@code 2} — JSONL parse error</li>
 *   <li>{@code 3} — Report file write error</li>
 *   <li>{@code 4} — Unexpected error</li>
 * </ul>
 */
public final class Main {

    static final int EXIT_SUCCESS = 0;
    static final int EXIT_BAD_ARGS = 1;
    static final int EXIT_PARSE_ERROR = 2;
    static final int EXIT_WRITE_ERROR = 3;
    static final int EXIT_UNEXPECTED = 4;

    private Main() {
    }

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

        // 4. Execute report pipeline
        try {
            Path reportPath = BpmCliReportPipeline.execute(cli);
            // Print report path to stdout (machine-parseable)
            System.out.println(reportPath.toAbsolutePath());
            System.exit(EXIT_SUCCESS);

        } catch (BpmParseException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(EXIT_PARSE_ERROR);
        } catch (IOException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(EXIT_WRITE_ERROR);
        } catch (Exception e) {
            System.err.println("ERROR: Unexpected failure: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
            e.printStackTrace(System.err);
            System.exit(EXIT_UNEXPECTED);
        }
    }
}
