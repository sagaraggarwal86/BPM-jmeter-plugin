package io.github.sagaraggarwal86.jmeter.bpm.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Zero-dependency command-line argument parser for the BPM Report Generator.
 *
 * <p>Generates a browser performance analysis report from a
 * BPM JSONL results file. Designed as a standalone post-processing tool
 * that runs after a JMeter test.</p>
 *
 * <p>Accumulates all errors (not fail-fast) so the user sees every problem
 * in a single invocation.</p>
 */
public final class CliArgs {

    private final List<String> errors = new ArrayList<>();
    // ── Required ─────────────────────────────────────────────────────────────
    private Path inputFile;
    // ── Output ───────────────────────────────────────────────────────────────
    private Path outputFile;
    // ── Filter Options ───────────────────────────────────────────────────────
    private int chartInterval = 0;
    private String search;
    private boolean regex;
    private boolean exclude;
    // ── Report Metadata ──────────────────────────────────────────────────────
    private String scenarioName = "";
    private String description = "";
    private int virtualUsers = 0;
    // ── Help ─────────────────────────────────────────────────────────────────
    private boolean help;

    private CliArgs() {
    }

    /**
     * Parses command-line arguments and returns a populated CliArgs instance.
     * Call {@link #errors()} to check for validation failures before proceeding.
     */
    public static CliArgs parse(String[] args) {
        CliArgs cli = new CliArgs();
        cli.doParse(args);
        if (!cli.help) {
            cli.validate();
        }
        return cli;
    }

    /**
     * Prints usage help to stderr.
     */
    public static void printHelp() {
        System.out.println("""
                BPM Report Generator — Command-Line Interface
                
                Generates a browser performance analysis report
                from a BPM JSONL results file produced by JMeter with the BPM plugin.
                
                Step 1: Run your JMeter test (standard JMeter non-GUI):
                  jmeter -n -t test.jmx -l results.jtl -Jbpm.output=bpm-results.jsonl
                
                Step 2: Generate report:
                  bpm-report -i bpm-results.jsonl
                
                REQUIRED:
                  -i, --input FILE            BPM JSONL results file
                
                OUTPUT:
                  -o, --output FILE           HTML report path (default: bpm-report.html)
                
                FILTER OPTIONS:
                  --chart-interval INT        Seconds per chart bucket, 0=auto (default: 0)
                  --search STRING             Label filter text (include mode by default)
                  --regex                     Treat --search as regex
                  --exclude                   Exclude matching labels (default: include)
                
                REPORT METADATA:
                  --scenario-name STRING      Scenario name for report header
                  --description STRING        Scenario description
                  --virtual-users INT         Virtual user count for report header
                
                HELP:
                  -h, --help                  Show this help message
                
                EXAMPLES:
                  bpm-report -i bpm-results.jsonl
                  bpm-report -i bpm-results.jsonl -o report.html \\
                    --scenario-name "Sprint 42" --description "Peak load test" --virtual-users 50
                  bpm-report -i bpm-results.jsonl --search "Login|Checkout" --regex
                
                EXIT CODES:
                  0  Success — report generated
                  1  Invalid command-line arguments
                  2  JSONL parse error
                  3  Report file write error
                  4  Unexpected error
                """);
    }

    private void doParse(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            switch (token) {
                case "-h", "--help" -> help = true;

                // Required
                case "-i", "--input" -> inputFile = nextPath(args, i++, token);

                // Output
                case "-o", "--output" -> outputFile = nextPath(args, i++, token);

                // Filter Options
                case "--chart-interval" -> chartInterval = nextInt(args, i++, token);
                case "--search" -> search = nextValue(args, i++, token);
                case "--regex" -> regex = true;
                case "--exclude" -> exclude = true;

                // Report Metadata
                case "--scenario-name" -> scenarioName = Objects.requireNonNullElse(nextValue(args, i++, token), "");
                case "--description" -> description = Objects.requireNonNullElse(nextValue(args, i++, token), "");
                case "--virtual-users" -> virtualUsers = nextInt(args, i++, token);

                default -> errors.add("Unknown argument: " + token);
            }
        }
    }

    // ── Argument helpers ─────────────────────────────────────────────────────

    private void validate() {
        // -i is required
        if (inputFile == null) {
            errors.add("--input is required. Provide a BPM JSONL file path.");
        } else if (!Files.isRegularFile(inputFile)) {
            errors.add("--input file does not exist: " + inputFile);
        }

        // --chart-interval: 0-3600
        if (chartInterval < 0 || chartInterval > 3600) {
            errors.add("--chart-interval must be between 0 and 3600.");
        }

        // --virtual-users: non-negative
        if (virtualUsers < 0) {
            errors.add("--virtual-users must be non-negative.");
        }

        // --regex: validate syntax early so bad patterns produce exit code 1, not 5
        if (regex && search != null && !search.isEmpty()) {
            try {
                java.util.regex.Pattern.compile(search);
            } catch (java.util.regex.PatternSyntaxException e) {
                errors.add("--search regex is invalid: " + e.getDescription());
            }
        }
    }

    private String nextValue(String[] args, int index, String flag) {
        if (index + 1 < args.length) return args[index + 1];
        errors.add(flag + " requires a value.");
        return null;
    }

    private Path nextPath(String[] args, int index, String flag) {
        String v = nextValue(args, index, flag);
        return v != null ? Path.of(v) : null;
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    private int nextInt(String[] args, int index, String flag) {
        String v = nextValue(args, index, flag);
        if (v == null) return 0;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            errors.add(flag + " requires an integer value, got: " + v);
            return 0;
        }
    }

    /**
     * BPM JSONL results file (required).
     */
    public Path inputFile() {
        return inputFile;
    }

    /**
     * HTML report output path, or {@code null} if not specified by the user.
     * When null, the pipeline generates a default name.
     */
    public Path outputFile() {
        return outputFile;
    }

    /**
     * Seconds per chart bucket. 0 = auto.
     */
    public int chartInterval() {
        return chartInterval;
    }

    /**
     * Label filter text. Null = no filtering.
     */
    public String search() {
        return search;
    }

    /**
     * Whether {@link #search()} should be treated as regex.
     */
    public boolean regex() {
        return regex;
    }

    /**
     * Whether {@link #search()} should exclude matching labels.
     */
    public boolean exclude() {
        return exclude;
    }

    /**
     * Scenario name for report header. Empty if not provided.
     */
    public String scenarioName() {
        return scenarioName;
    }

    /**
     * Scenario description for report header. Empty if not provided.
     */
    public String description() {
        return description;
    }

    /**
     * Virtual user count for report header. 0 if not provided.
     */
    public int virtualUsers() {
        return virtualUsers;
    }

    public boolean help() {
        return help;
    }

    public List<String> errors() {
        return errors;
    }
}
