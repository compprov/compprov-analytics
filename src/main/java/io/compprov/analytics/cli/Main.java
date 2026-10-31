package io.compprov.analytics.cli;

import dev.langchain4j.model.chat.BatchChatModel;
import dev.langchain4j.model.chat.ChatModel;
import io.compprov.analytics.ai.Prompt;
import io.compprov.analytics.processing.BatchData;
import io.compprov.analytics.processing.BatchExecutor;
import io.compprov.analytics.processing.Generator;
import io.compprov.analytics.processing.ParallelSyncExecutor;
import io.compprov.analytics.processing.ProcessingResult;
import io.compprov.analytics.processing.PromptProcessingMode;
import io.compprov.core.EnvironmentCustomizer;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;

import static io.compprov.analytics.Commons.ENV;
import static io.compprov.analytics.Commons.FOLDER_FORMAT;
import static io.compprov.analytics.Commons.LOGGER;
import static io.compprov.analytics.Commons.read;
import static io.compprov.analytics.Commons.save;
import static io.compprov.analytics.Commons.serialize;

/**
 * CLI entry point for compprov-analytics. For every {@code --cpgpath}/{@code --cpgfolder} snapshot, replays its
 * computation via compprov-core, runs deterministic structural checks (replay mismatch, multiple and
 * duplicate-named leaves, unused roots, multiple math contexts, multi-used variables, repeated arguments,
 * scaling operations, chronology violations) and builds the active {@link Prompt}s from the pruned
 * (optionally minified) graph. Depending on {@link PromptProcessingMode}, the prompts are only written as
 * markdown, sent through a plugin's {@link ChatModel}, or submitted as a batch through its
 * {@link BatchChatModel}. Output goes to {@code --workDir} or a new timestamped {@code <run>/out/} directory.
 */
public class Main {

    private static ChatModel chatModel = null;
    private static BatchChatModel batchChatModel = null;
    private static PromptProcessingMode promptProcessingMode = PromptProcessingMode.GENERATE_MARKDOWN;
    private static List<Prompt> activePrompts = List.of(Prompt.TOPOLOGICAL_FRAUD, Prompt.PRECISION_TAMPERING, Prompt.SEMANTIC_VIOLATION);
    private static Double cleanThreshold = 25.0;
    private static Double suspiciousThreshold = 70.0;
    private static final Map<String, String> templateOverrides = new HashMap<>();
    private static final Set<String> TEMPLATE_OVERRIDE_KEYS = templateOverrideKeys();
    private static int threads = 1;
    private static boolean minify = false;
    private static final List<String> OPTIONS = List.of("--threads=", "--minify=", "--plugin=", "--promptProcessingMode=",
            "--thresholds=", "--cpgpath=", "--workDir=", "--cpgfolder=", "--llmTemplates=");

    private static Set<String> templateOverrideKeys() {
        final var keys = new HashSet<String>();
        for (var prompt : Prompt.values()) {
            keys.add(prompt.markdownTemplateKey());
            keys.add(prompt.jsonUserTemplateKey());
            keys.add(prompt.jsonSystemTemplateKey());
        }
        return keys;
    }

    public static void main(String[] args) {

        String workDir = null;

        //no args
        if (args.length == 0) {
            LOGGER.severe("""
                    Usage: java -jar compprov-analytics.jar [--cpgpath=<path-to-cpg-file> ...] [--cpgfolder=<path-to-folder> ...] [--plugin=<path-to-plugin-jar>] [--promptProcessingMode=<mode>] [--workDir=<dir>] [--thresholds=<clean>,<suspicious>] [--threads=<count>] [--minify=<true|false>] [--llmTemplates=<name>[,<name>...]] [--<templateKey>=<path> ...]
                      --cpgpath=<path>            Path to a CPG JSON file to analyze. Repeatable; at least one --cpgpath/--cpgfolder is required.
                      --cpgfolder=<path>          Directory to scan recursively for *.json CPG files (extension matched case-insensitively). Every match is processed as a separate file. Repeatable.
                      --plugin=<path>             Path to a plugin JAR providing EnvironmentCustomizer/ChatModel/BatchChatModel implementations. Optional, repeatable.
                      --promptProcessingMode=<mode> Optional, defaults to GENERATE_MARKDOWN. One of:
                                                  GENERATE_MARKDOWN - only generate markdown prompts for manual use;
                                                  PROCESS_SYNC      - send the prompts through the plugin's ChatModel;
                                                  EXECUTE_ASYNC     - submit the prompts as a batch through the plugin's BatchChatModel,
                                                                      save <workDir>/batch.json and exit;
                                                  PROCESS_ASYNC     - submit a batch and wait for its results;
                                                  CONTINUE_ASYNC    - wait for and process the batch saved in <workDir>/batch.json.
                      --workDir=<dir>             Existing output folder. Optional, defaults to <timestamp>/out. Required with CONTINUE_ASYNC.
                      --thresholds=<clean>,<suspicious> Overrides the risk_score cutoffs used to derive the CLEAN/SUSPICIOUS LOGIC/ANOMALY DETECTED
                                                  verdict label (a risk_score below <clean> is CLEAN, below <suspicious> is SUSPICIOUS LOGIC,
                                                  otherwise ANOMALY DETECTED). Optional, defaults to 25,70. <clean> must be within (0, 100)
                                                  and <suspicious> within (<clean>, 100].
                      --threads=<count>           Number of CPG files whose prompts are sent in parallel in PROCESS_SYNC mode.
                                                  Optional, defaults to 1. Prompts of a single file still run one after another.
                      --minify=<true|false>       Embed the pruned CPG into prompts as compact JSON (no indentation or line breaks).
                                                  Optional, defaults to false.
                      --llmTemplates=<names>      Comma-separated template names to generate/execute, e.g. calculation_omission,precision_tampering.
                                                  Optional; defaults to all: %s
                      --<templateKey>=<path>      Override a bundled prompt template with a file from disk, e.g.
                                                  --calculation_omission_user=/path/to/my_template.md replaces the
                                                  bundled calculation_omission_user.md. Optional, repeatable. Valid
                                                  template keys: %s""".formatted(
                    String.join(", ", Arrays.stream(Prompt.values()).map(Prompt::getTemplateName).toList()),
                    String.join(", ", TEMPLATE_OVERRIDE_KEYS.stream().sorted().toList())));

            System.exit(1);
        }

        //reject unknown arguments, so a misspelled option doesn't silently fall back to its default
        for (var arg : args) {
            if (!isKnownArgument(arg)) {
                LOGGER.severe("Unknown argument: " + arg + ". Run without arguments to print the usage");
                return;
            }
        }

        //plugins
        final var cpgFiles = new ArrayList<String>();
        for (var arg : args) {
            if (arg.startsWith("--threads=")) {
                try {
                    final var count = Integer.parseInt(arg.substring("--threads=".length()));
                    if (count < 1) {
                        throw new IllegalArgumentException("threads count must be at least 1, got " + count);
                    }
                    threads = count;
                } catch (Throwable th) {
                    LOGGER.log(Level.SEVERE, "Unable to parse threads: " + arg, th);
                    return;
                }
            }
            if (arg.startsWith("--minify=")) {
                final var value = arg.substring("--minify=".length()).trim().toLowerCase(Locale.ROOT);
                if (!value.equals("true") && !value.equals("false")) {
                    LOGGER.severe("Unable to parse minify, expected true or false: " + arg);
                    return;
                }
                minify = Boolean.parseBoolean(value);
            }
            if (arg.startsWith("--plugin=")) {
                try {
                    processPlugin(arg.substring("--plugin=".length()));
                } catch (Throwable th) {
                    LOGGER.log(Level.SEVERE, "Unable to process plugin: " + arg, th);
                    return;
                }
            }
            if (arg.startsWith("--promptProcessingMode=")) {
                try {
                    promptProcessingMode = PromptProcessingMode.valueOf(arg.substring("--promptProcessingMode=".length()));
                } catch (Throwable th) {
                    LOGGER.log(Level.SEVERE, "Unable to parse promptProcessingMode: " + arg, th);
                    return;
                }
            }
            if (arg.startsWith("--thresholds=")) {
                try {
                    final var parts = arg.substring("--thresholds=".length()).split(",");
                    if (parts.length != 2) {
                        throw new IllegalArgumentException("expected exactly two comma-separated values, e.g. --thresholds=30,80");
                    }
                    final var clean = Double.parseDouble(parts[0].trim());
                    final var suspicious = Double.parseDouble(parts[1].trim());
                    if (clean <= 0 || clean >= 100) {
                        throw new IllegalArgumentException("clean threshold must be within (0, 100), got " + clean);
                    }
                    if (suspicious <= clean || suspicious > 100) {
                        throw new IllegalArgumentException("suspicious threshold must be within (clean, 100], got " + suspicious);
                    }
                    cleanThreshold = clean;
                    suspiciousThreshold = suspicious;
                } catch (Throwable th) {
                    LOGGER.log(Level.SEVERE, "Unable to parse thresholds: " + arg, th);
                    return;
                }
            }
            if (arg.startsWith("--cpgpath=")) {
                try {
                    cpgFiles.add(arg.substring("--cpgpath=".length()));
                } catch (Throwable th) {
                    LOGGER.log(Level.SEVERE, "Unable to process cpgpath argument: " + arg, th);
                    return;
                }
            }
            if (arg.startsWith("--workDir=")) {
                try {
                    workDir = arg.substring("--workDir=".length());
                    if (!new File(workDir).isDirectory()) {
                        throw new Exception("Specified workDir is not dir: " + workDir);
                    }
                } catch (Throwable th) {
                    LOGGER.log(Level.SEVERE, "Unable to process workDir argument: " + arg, th);
                    return;
                }
            }
            if (arg.startsWith("--cpgfolder=")) {
                try {
                    final var folder = arg.substring("--cpgfolder=".length());
                    final var found = scanCpgFolder(folder);
                    LOGGER.info("Found %d CPG file(s) in folder: %s".formatted(found.size(), folder));
                    cpgFiles.addAll(found);
                } catch (Throwable th) {
                    LOGGER.log(Level.SEVERE, "Unable to process folder: " + arg, th);
                    return;
                }
            }
            if (arg.startsWith("--llmTemplates=")) {
                try {
                    activePrompts = Arrays.stream(arg.substring("--llmTemplates=".length()).split(","))
                            .map(String::trim)
                            .filter(name -> !name.isEmpty())
                            .map(Prompt::fromTemplateName)
                            .toList();
                } catch (Throwable th) {
                    LOGGER.log(Level.SEVERE, "Unable to parse llmTemplates: " + arg, th);
                    return;
                }
            }
            if (arg.startsWith("--")) {
                final var eq = arg.indexOf('=');
                if (eq > 2) {
                    final var key = arg.substring(2, eq);
                    if (TEMPLATE_OVERRIDE_KEYS.contains(key)) {
                        templateOverrides.put(key, arg.substring(eq + 1));
                    }
                }
            }
        }

        //check chat models
        if ((chatModel == null) && (promptProcessingMode == PromptProcessingMode.PROCESS_SYNC)) {
            LOGGER.severe("Chat model should be configured when promptProcessingMode is set to PROCESS_SYNC");
            return;
        }
        if (batchChatModel == null) {
            if ((promptProcessingMode == PromptProcessingMode.EXECUTE_ASYNC)
                    || (promptProcessingMode == PromptProcessingMode.PROCESS_ASYNC)
                    || (promptProcessingMode == PromptProcessingMode.CONTINUE_ASYNC)) {
                LOGGER.severe("Batch chat model should be configured when promptProcessingMode is set to EXECUTE_ASYNC|PROCESS_ASYNC|CONTINUE_ASYNC");
                return;
            }
        }

        //check input files
        if (cpgFiles.isEmpty() && (promptProcessingMode != PromptProcessingMode.CONTINUE_ASYNC)) {
            LOGGER.severe("No CPG files to process, specify at least one --cpgpath or a --cpgfolder containing *.json files");
            return;
        }

        //check workDir
        if (workDir == null) {
            if (promptProcessingMode == PromptProcessingMode.CONTINUE_ASYNC) {
                LOGGER.severe("workDir must be specified when promptProcessingMode is set to CONTINUE_ASYNC");
                return;
            }
            workDir = ZonedDateTime.now().format(FOLDER_FORMAT) + "/out";
        } else if (workDir.endsWith("\\") || (workDir.endsWith("/"))) {
            workDir = workDir.substring(0, workDir.length() - 1);
        }

        //generate
        final List<ProcessingResult> preProcessed;
        try {
            preProcessed = switch (promptProcessingMode) {
                case GENERATE_MARKDOWN, EXECUTE_ASYNC, PROCESS_ASYNC, PROCESS_SYNC ->
                        new Generator(workDir, promptProcessingMode, activePrompts, templateOverrides, minify).generate(cpgFiles);
                default -> null;
            };
        } catch (Throwable th) {
            LOGGER.log(Level.SEVERE, "Failed to pre-process CPG files", th);
            return;
        }

        //execute async
        try {
            switch (promptProcessingMode) {
                case EXECUTE_ASYNC, PROCESS_ASYNC -> {
                    BatchExecutor executor = new BatchExecutor(activePrompts, batchChatModel, cleanThreshold, suspiciousThreshold);
                    final var batchData = executor.submit(preProcessed);
                    save(serialize(batchData), workDir + "/batch.json");
                }
            }
        } catch (Throwable th) {
            LOGGER.log(Level.SEVERE, "Failed to execute", th);
            return;
        }

        //fetch results
        List<ProcessingResult> results;
        try {
            results = switch (promptProcessingMode) {
                case GENERATE_MARKDOWN -> preProcessed;
                case PROCESS_SYNC -> new ParallelSyncExecutor(activePrompts, chatModel,
                        cleanThreshold, suspiciousThreshold).processAndShutdown(preProcessed, threads);
                case PROCESS_ASYNC, CONTINUE_ASYNC -> {
                    final var batchData = ENV.getMapper().readValue(read(workDir + "/batch.json"), BatchData.class);
                    activePrompts = batchData.activePrompts();
                    yield new BatchExecutor(activePrompts, batchChatModel,
                            cleanThreshold, suspiciousThreshold).process(batchData);
                }
                default -> null;
            };
        } catch (Throwable th) {
            LOGGER.log(Level.SEVERE, "Failed to fetch results", th);
            return;
        }

        //aggregate results
        if (results != null) {
            try {
                for (var r : results) {
                    save(buildFileSummary(r), r.getProcessingDir() + "/summary.md");
                    LOGGER.info("File processed: %s. Highlights count: %d. Calculation valid: %b. Chronology valid: %b".formatted(
                            r.getSourceFile(),
                            r.getHighlights().size(),
                            r.isValidCalculation(),
                            r.isValidChronology()));
                }

                save(aggregateResults(results), workDir + "/summary.md");
                LOGGER.info("Processing is done, the report is saved into " + workDir + "/summary.md");

            } catch (Throwable th) {
                LOGGER.log(Level.SEVERE, "Failed to aggregate results", th);
            }
        }
    }

    private static boolean isKnownArgument(String arg) {
        if (OPTIONS.stream().anyMatch(arg::startsWith)) {
            return true;
        }
        final var eq = arg.indexOf('=');
        return arg.startsWith("--") && eq > 2 && TEMPLATE_OVERRIDE_KEYS.contains(arg.substring(2, eq));
    }

    private static List<String> scanCpgFolder(String folderPath) throws IOException {
        final var dir = Path.of(folderPath);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + folderPath);
        }
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }
    }

    private static String buildFileSummary(ProcessingResult result) {
        final var sb = new StringBuilder();
        sb.append("# Compprov Analytics Report: ").append(result.getSourceFile()).append("\n\n");
        sb.append("Generated: ").append(ZonedDateTime.now(ZoneId.of("UTC"))).append("\n\n");
        sb.append("**Calculation validity**: ")
                .append(result.isValidCalculation() ? "✅ Valid" : "❌ Invalid")
                .append("\n\n");
        sb.append("**Chronology validity**: ")
                .append(result.isValidChronology() ? "✅ Valid" : "❌ Invalid")
                .append("\n\n");

        sb.append("## Structural Highlights\n\n");
        if (result.getHighlights().isEmpty()) {
            sb.append("No structural issues detected.\n\n");
        } else {
            for (var highlight : result.getHighlights()) {
                sb.append("- ").append(highlight).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## Fraud-Pattern Analysis\n\n");
        if (promptProcessingMode == PromptProcessingMode.GENERATE_MARKDOWN) {
            appendManualPromptsSection(sb);
        } else {
            sb.append("| Prompt | Verdict | Confidence | Report |\n|---|---|---|---|\n");
            for (var prompt : activePrompts) {
                final var llmResult = result.getLlmResults().get(prompt);
                if (result.isFailed() || (llmResult != null && llmResult.isFailed())) {
                    sb.append("| ").append(prompt.getDescription()).append(" | FAILED | — | — |\n");
                } else if (llmResult == null) {
                    sb.append("| ").append(prompt.getDescription()).append(" | — | — | — |\n");
                } else {
                    sb.append("| ").append(prompt.getDescription())
                            .append(" | ").append(llmResult.verdict())
                            .append(" | ").append(llmResult.riskScore())
                            .append(" | `").append(prompt.markdownResultFilename()).append("` |\n");
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private static String aggregateResults(List<ProcessingResult> results) {
        final var sb = new StringBuilder();
        sb.append("# Compprov Analytics Summary\n\n");
        sb.append("Generated: ").append(ZonedDateTime.now(ZoneId.of("UTC"))).append("\n\n");

        if (results.isEmpty()) {
            sb.append("No CPG files were processed.\n");
            return sb.toString();
        }

        sb.append("## Overview\n\n");
        sb.append("| № | File | Calculation | Chronology | Highlights |");
        for (var prompt : activePrompts) {
            sb.append(" ").append(prompt.getDescription()).append(" |");
        }
        sb.append("\n|---|---|---|---|---|");
        for (var ignored : activePrompts) {
            sb.append("---|");
        }
        sb.append("\n");

        int i = 0;
        for (var result : results) {
            sb.append("| ")
                    .append(++i).append(" | ")
                    .append(result.getSourceFile()).append(" | ")
                    .append(result.isValidCalculation() ? "✅ Valid" : "❌ Invalid").append(" | ")
                    .append(result.isValidChronology() ? "✅ Valid" : "❌ Invalid").append(" | ")
                    .append(result.getHighlights().size()).append(" |");
            for (var prompt : activePrompts) {
                final var llmResult = result.getLlmResults().get(prompt);
                if (result.isFailed()) {
                    sb.append(" FAILED |");
                } else if (llmResult == null) {
                    sb.append(" — |");
                } else {
                    sb.append(" ").append(llmResult.display()).append(" |");
                }
            }
            sb.append("\n");
        }

        sb.append("\n## Highlights\n\n");
        for (var result : results) {
            if (result.getHighlights().isEmpty() && !result.isFailed()) {
                continue;
            }
            sb.append("### ").append(result.getSourceFile()).append("\n\n");
            if (result.isFailed()) {
                sb.append("- Prompt processing failed, see the log for details.\n");
            }
            for (var highlight : result.getHighlights()) {
                sb.append("- ").append(highlight).append("\n");
            }
            sb.append("\n");
        }

        if (promptProcessingMode == PromptProcessingMode.GENERATE_MARKDOWN) {
            sb.append("\n## Fraud-Pattern Analysis\n\n");
            appendManualPromptsSection(sb);
        }

        return sb.toString();
    }

    private static void appendManualPromptsSection(StringBuilder sb) {
        sb.append("Prompt processing mode is GENERATE_MARKDOWN, so the fraud-pattern prompts below were generated ")
                .append("but not sent to an LLM. You can either:\n\n")
                .append("- copy each prompt file listed below into the user interface of an LLM of your choice ")
                .append("and review the response manually, or\n")
                .append("- re-run this tool with `--plugin=<path-to-plugin-jar>` pointing to a plugin that ")
                .append("supplies a `ChatModel` or `BatchChatModel` implementation and `--promptProcessingMode=` ")
                .append("set to `PROCESS_SYNC` or `PROCESS_ASYNC`, so the prompts are sent automatically and verdicts ")
                .append("appear in this report.\n\n");
        sb.append("| Prompt | Prompt file |\n|---|---|\n");
        for (var prompt : activePrompts) {
            sb.append("| ").append(prompt.getDescription())
                    .append(" | `").append(prompt.promptFilename()).append("` |\n");
        }
        sb.append("\n");
    }

    private static void processPlugin(String pluginAddress) throws IOException {
        final var jarFile = new File(pluginAddress);
        if (!jarFile.exists()) {
            throw new IllegalArgumentException("Plugin JAR not found at: " + pluginAddress);
        }

        final var jarUrl = jarFile.toURI().toURL();
        final var parentClassLoader = Main.class.getClassLoader();

        // Intentionally not closed here: classes registered by the customizer
        // (e.g. Jackson deserializers) may be resolved lazily, well after this
        // method returns, while files are being processed. Closing the loader
        // early leaves it unable to define those classes on demand.
        final var pluginClassLoader = new URLClassLoader(new URL[]{jarUrl}, parentClassLoader);

        //env customizer
        ServiceLoader<EnvironmentCustomizer> loader = ServiceLoader.load(
                EnvironmentCustomizer.class,
                pluginClassLoader
        );
        var found = false;
        for (EnvironmentCustomizer customizer : loader) {
            LOGGER.info("Applying customizer: " + customizer.getClass().getName());
            customizer.customize(ENV);
            found = true;
        }

        //chat model
        ServiceLoader<ChatModel> cmLoader = ServiceLoader.load(
                ChatModel.class,
                pluginClassLoader
        );
        for (ChatModel cm : cmLoader) {
            LOGGER.info("Using chat model");
            chatModel = cm;
            found = true;
        }

        //batch chat model
        ServiceLoader<BatchChatModel> bcmLoader = ServiceLoader.load(
                BatchChatModel.class,
                pluginClassLoader
        );
        for (var bcm : bcmLoader) {
            LOGGER.info("Using batch chat model");
            batchChatModel = bcm;
            found = true;
        }

        if (!found) {
            LOGGER.severe("Warning: no EnvironmentCustomizer, ChatModel or BatchChatModel implementation found in " + pluginAddress);
        }
    }
}
