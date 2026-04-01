package io.github.sagaraggarwal86.jmeter.bpm.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Zero-dependency command-line argument parser for the BPM CLI report generator.
 *
 * <p>Parses required and optional arguments, validates constraints, and
 * accumulates all errors (not fail-fast) so the user sees every problem
 * in a single invocation.</p>
 */
public final class CliArgs {

    private Path inputFile;
    private Path outputFile;
    private String provider;
    private Path configFile;
    private boolean help;
    private final List<String> errors = new ArrayList<>();

    private CliArgs() { }

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

    private void doParse(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            switch (token) {
                case "-h", "--help" -> help = true;
                case "-i", "--input" -> {
                    if (i + 1 < args.length) inputFile = Path.of(args[++i]);
                    else errors.add("--input requires a file path.");
                }
                case "-o", "--output" -> {
                    if (i + 1 < args.length) outputFile = Path.of(args[++i]);
                    else errors.add("--output requires a file path.");
                }
                case "--provider" -> {
                    if (i + 1 < args.length) provider = args[++i].trim().toLowerCase(java.util.Locale.ROOT);
                    else errors.add("--provider requires a provider name.");
                }
                case "--config" -> {
                    if (i + 1 < args.length) configFile = Path.of(args[++i]);
                    else errors.add("--config requires a file path.");
                }
                default -> errors.add("Unknown argument: " + token);
            }
        }
    }

    private void validate() {
        if (inputFile == null) {
            errors.add("--input is required. Provide a BPM JSONL file path.");
        } else if (!Files.isRegularFile(inputFile)) {
            errors.add("--input file does not exist: " + inputFile);
        }

        if (provider == null || provider.isBlank()) {
            errors.add("--provider is required. Example: --provider groq");
        }

        if (configFile == null) {
            errors.add("--config is required. Provide the path to ai-reporter.properties.");
        } else if (!Files.isRegularFile(configFile)) {
            errors.add("--config file does not exist: " + configFile);
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Path inputFile() { return inputFile; }

    public Path outputFile() {
        return outputFile != null ? outputFile : Path.of("bpm-ai-report.html");
    }

    public String provider() { return provider; }
    public Path configFile() { return configFile; }
    public boolean help() { return help; }
    public List<String> errors() { return errors; }

    /**
     * Prints usage help to stderr.
     */
    public static void printHelp() {
        System.err.println("""
                BPM AI Report Generator — Command-Line Interface

                Generates an AI-powered browser performance analysis report
                from a BPM JSONL results file.

                REQUIRED:
                  -i, --input FILE      BPM JSONL results file (e.g., bpm-results.jsonl)
                  --provider NAME       AI provider name (e.g., groq, openai, claude)
                  --config FILE         Path to ai-reporter.properties

                OPTIONAL:
                  -o, --output FILE     HTML report output path (default: bpm-ai-report.html)
                  -h, --help            Show this help message

                EXAMPLES:
                  bpm-cli-report.sh -i results.jsonl --provider groq --config ai-reporter.properties
                  bpm-cli-report.sh -i results.jsonl --provider openai --config ai-reporter.properties -o report.html

                EXIT CODES:
                  0  Success — report generated
                  1  Invalid command-line arguments
                  2  JSONL parse error
                  3  AI provider error (invalid key, ping failed, API error)
                  4  Report file write error
                  5  Unexpected error
                """);
    }
}
