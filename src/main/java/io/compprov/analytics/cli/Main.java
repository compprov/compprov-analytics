package io.compprov.analytics.cli;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.FinishReason;
import io.compprov.analytics.ProcessingResult;
import io.compprov.analytics.ai.Prompt;
import io.compprov.analytics.ai.PromptProcessingResult;
import io.compprov.analytics.ai.ReducedPromptProcessingResult;
import io.compprov.core.ComputationEnvironment;
import io.compprov.core.DefaultComputationEnvironment;
import io.compprov.core.EnvironmentCustomizer;
import io.compprov.core.Snapshot;
import io.compprov.core.meta.Descriptor;
import io.compprov.tools.SnapshotNavigator;
import tools.jackson.core.JacksonException;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.MathContext;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * CLI entry point for compprov-analytics. For every {@code --cpgpath} snapshot, replays its
 * computation via compprov-core, runs a set of deterministic structural checks (replay mismatch,
 * orphaned/duplicate-named leaves, unused roots, multiple math contexts, multi-used variables,
 * scaling operations, chronology violations), and — if a {@link ChatModel} was supplied by a
 * {@code --plugin} — sends the graph through five LLM fraud-pattern prompts defined in
 * {@link Prompt}. Every run's output is written under a timestamped {@code <run>/out/} directory
 * so repeated runs never overwrite each other.
 */
public class Main {

    private static final DateTimeFormatter folderFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss.SSS");
    private static final Logger LOGGER = Logger.getLogger("analytics-main");
    private static final ComputationEnvironment ENV = DefaultComputationEnvironment.create();
    private static ChatModel chatModel = null;
    private static boolean executePrompts = true;
    private static int intercallTimeoutMs = 0;
    private static List<Prompt> activePrompts = List.of(Prompt.TOPOLOGICAL_FRAUD, Prompt.PRECISION_TAMPERING, Prompt.SEMANTIC_VIOLATION);
    private static final Map<String, String> templateOverrides = new HashMap<>();
    private static final Set<String> TEMPLATE_OVERRIDE_KEYS = templateOverrideKeys();

    /**
     * All valid {@code --<key>=<path>} template-override keys: each {@link Prompt}'s markdown and
     * JSON user template, plus the shared JSON system template. See {@link Prompt#markdownTemplateKey()},
     * {@link Prompt#jsonUserTemplateKey()}, {@link Prompt#sharedJsonSystemTemplateKey()}.
     */
    private static Set<String> templateOverrideKeys() {
        final var keys = new java.util.HashSet<String>();
        keys.add(Prompt.sharedJsonSystemTemplateKey());
        for (var prompt : Prompt.values()) {
            keys.add(prompt.markdownTemplateKey());
            keys.add(prompt.jsonUserTemplateKey());
        }
        return keys;
    }

    static {
        LOGGER.setUseParentHandlers(false);
        final var handler = new ConsoleHandler();
        handler.setFormatter(new Formatter() {
            private final DateTimeFormatter timestampFormat = DateTimeFormatter.ISO_DATE_TIME;

            @Override
            public String format(LogRecord record) {
                final var timestamp = timestampFormat.format(ZonedDateTime.ofInstant(record.getInstant(), ZoneId.systemDefault()));
                final var sb = new StringBuilder()
                        .append(timestamp).append(' ')
                        .append(record.getSourceClassName()).append(' ')
                        .append(record.getSourceMethodName()).append(' ')
                        .append(record.getLevel()).append(": ")
                        .append(formatMessage(record))
                        .append(System.lineSeparator());
                if (record.getThrown() != null) {
                    final var sw = new StringWriter();
                    record.getThrown().printStackTrace(new PrintWriter(sw));
                    sb.append(sw).append(System.lineSeparator());
                }
                return sb.toString();
            }
        });
        LOGGER.addHandler(handler);
    }

    /**
     * Parses CLI arguments, applies any {@code --plugin} jars, then processes every
     * {@code --cpgpath} snapshot and writes the per-file and aggregate reports. Prints usage and
     * exits with status {@code 1} if no arguments are given.
     *
     * @param args {@code --cpgpath=}, {@code --cpgfolder=}, {@code --plugin=},
     *             {@code --executePrompts=}, {@code --intercallTimeoutMs=}, {@code --llmTemplates=},
     *             and per-template {@code --<templateKey>=<path>} override arguments; see the
     *             printed usage for details
     */
    public static void main(String[] args) {

        //no args
        if (args.length == 0) {
            LOGGER.severe("""
                    Usage: java -jar compprov-analytics.jar [--cpgpath=<path-to-cpg-file> ...] [--cpgfolder=<path-to-folder> ...] [--plugin=<path-to-plugin-jar>] [--executePrompts=<true/false>] [--intercallTimeoutMs=<ms>] [--llmTemplates=<name>[,<name>...]] [--<templateKey>=<path> ...]
                      --cpgpath=<path>            Path to a CPG JSON file to analyze. Repeatable; at least one --cpgpath/--cpgfolder is required.
                      --cpgfolder=<path>          Directory to scan recursively for *.json CPG files (extension matched case-insensitively). Every match is processed as a separate file. Repeatable.
                      --plugin=<path>             Path to a plugin JAR providing EnvironmentCustomizer/ChatModel implementations. Optional, repeatable.
                      --executePrompts=true/false Default true. False will skip prompt execution even if ChatModel is set.
                      --intercallTimeoutMs=<ms>   Pause (in milliseconds) before each chat model call. Optional, defaults to 0.
                      --llmTemplates=<names>      Comma-separated template names to generate/execute, e.g. calculation_omission,precision_tampering.
                                                  Optional; defaults to all: %s
                      --<templateKey>=<path>      Override a bundled prompt template with a file from disk, e.g.
                                                  --calculation_omission_user=/path/to/my_template.md replaces the
                                                  bundled calculation_omission_user.md. Optional, repeatable. Valid
                                                  template keys: %s""".formatted(
                    String.join(", ", java.util.Arrays.stream(Prompt.values()).map(Prompt::getTemplateName).toList()),
                    String.join(", ", TEMPLATE_OVERRIDE_KEYS.stream().sorted().toList())));

            System.exit(1);
        }

        //plugins
        final var cpgFiles = new ArrayList<String>();
        for (var arg : args) {
            if (arg.startsWith("--plugin=")) {
                try {
                    processPlugin(arg.substring("--plugin=".length()));
                } catch (Throwable th) {
                    LOGGER.log(Level.SEVERE, "Unable to process plugin: " + arg, th);
                }
            }
            if (arg.startsWith("--intercallTimeoutMs=")) {
                try {
                    intercallTimeoutMs = Integer.parseInt(arg.substring("--intercallTimeoutMs=".length()));
                } catch (Throwable th) {
                    LOGGER.log(Level.SEVERE, "Unable to parse intercallTimeoutMs: " + arg, th);
                }
            }
            if (arg.startsWith("--executePrompts=")) {
                try {
                    executePrompts = Boolean.parseBoolean(arg.substring("--executePrompts=".length()));
                } catch (Throwable th) {
                    LOGGER.log(Level.SEVERE, "Unable to parse executePrompts: " + arg, th);
                }
            }
            if (arg.startsWith("--cpgpath=")) {
                try {
                    cpgFiles.add(arg.substring("--cpgpath=".length()));
                } catch (Throwable th) {
                    LOGGER.log(Level.SEVERE, "Unable to process file: " + arg, th);
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
                }
            }
            if (arg.startsWith("--llmTemplates=")) {
                try {
                    activePrompts = java.util.Arrays.stream(arg.substring("--llmTemplates=".length()).split(","))
                            .map(String::trim)
                            .filter(name -> !name.isEmpty())
                            .map(Prompt::fromTemplateName)
                            .toList();
                } catch (Throwable th) {
                    LOGGER.log(Level.SEVERE, "Unable to parse llmTemplates: " + arg, th);
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
        if (!executePrompts) {
            chatModel = null;
        }
        if (chatModel == null) {
            LOGGER.info("The chat model is not set or prompts execution is disabled, but the prompts will be generated anyway and could be executed manually.");
        }

        //files
        String prepath = ZonedDateTime.now().format(folderFormat) + "/out";
        Map<String, ProcessingResult> result = new LinkedHashMap<>();
        for (int i = 0; i < cpgFiles.size(); i++) {
            final var filename = cpgFiles.get(i);
            try {
                LOGGER.info("Processing file %d of %d: %s".formatted(i + 1, cpgFiles.size(), filename));
                var r = processFile(prepath, i + 1, filename);
                result.put(filename, r);
                save(buildFileSummary(filename, r), r.getProcessingDir() + "/summary.md");
                LOGGER.info("File processed. Highlights count: %d. Calculation valid: %b. Chronology valid: %b".formatted(
                        r.getHighlights().size(),
                        r.isValidCalculation(),
                        r.isValidChronology()));
            } catch (Throwable th) {
                LOGGER.log(Level.SEVERE, "Unable to process file: " + filename, th);
                result.put(filename, null);
            }
        }

        //aggregate results for all files
        new File(prepath).mkdirs();
        save(aggregateResults(result), prepath + "/summary.md");
        LOGGER.info("Processing is done, the report is saved into " + prepath + "/summary.md");
    }

    /**
     * Recursively scans {@code folderPath} for files whose extension is {@code .json}
     * (case-insensitive), each treated as a separate CPG snapshot to process.
     *
     * @return the matching file paths, sorted for deterministic processing order
     * @throws IOException if {@code folderPath} doesn't exist, isn't a directory, or can't be walked
     */
    private static List<String> scanCpgFolder(String folderPath) throws IOException {
        final var dir = Path.of(folderPath);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + folderPath);
        }
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json"))
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }
    }

    /**
     * Builds the top-level {@code summary.md}: an overview table (one row per processed file,
     * one column per {@link Prompt}) followed by every file's structural highlights.
     */
    private static String aggregateResults(Map<String, ProcessingResult> results) {
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
        for (var entry : results.entrySet()) {
            final var file = entry.getKey();
            final var result = entry.getValue();
            LinkedHashMap<Prompt, ReducedPromptProcessingResult> llmResults;
            if (result == null) {
                sb.append("| ")
                        .append(++i).append(" | ")
                        .append(file).append(" | ")
                        .append(" — | — | — |");
                llmResults = new LinkedHashMap<>();
            } else {
                sb.append("| ")
                        .append(++i).append(" | ")
                        .append(file).append(" | ")
                        .append(result.isValidCalculation() ? "✅ Valid" : "❌ Invalid").append(" | ")
                        .append(result.isValidChronology() ? "✅ Valid" : "❌ Invalid").append(" | ")
                        .append(result.getHighlights().size()).append(" |");
                llmResults = result.getLlmResults();
            }
            for (var prompt : activePrompts) {
                final var llmResult = llmResults.get(prompt);
                if (llmResult == null) {
                    sb.append(" — |");
                } else {
                    sb.append(" ").append(llmResult.verdict())
                            .append(" (").append(llmResult.confidenceScore()).append(") |");
                }
            }
            sb.append("\n");
        }

        sb.append("\n## Highlights\n\n");
        for (var entry : results.entrySet()) {
            final var result = entry.getValue();
            if (result == null) {
                sb.append("### ").append(entry.getKey()).append("\n\n");
                sb.append("- ").append("File processing failed, result skipped.").append("\n");
                sb.append("\n");
                continue;
            }
            if (result.getHighlights().isEmpty()) {
                continue;
            }
            sb.append("### ").append(entry.getKey()).append("\n\n");
            for (var highlight : result.getHighlights()) {
                sb.append("- ").append(highlight).append("\n");
            }
            sb.append("\n");
        }

        if (chatModel == null) {
            sb.append("\n## Fraud-Pattern Analysis\n\n");
            sb.append("No chat model is configured, so the fraud-pattern prompts below were generated ")
                    .append("but not sent to an LLM. You can either:\n\n")
                    .append("- copy each prompt file listed below into the user interface of an LLM of your choice ")
                    .append("and review the response manually, or\n")
                    .append("- re-run this tool with `--plugin=<path-to-plugin-jar>` pointing to a plugin that ")
                    .append("supplies a `dev.langchain4j.model.chat.ChatModel` implementation, so the prompts are ")
                    .append("sent automatically and verdicts appear in this report.\n\n");
            sb.append("| Prompt | Prompt file |\n|---|---|\n");
            for (var prompt : activePrompts) {
                sb.append("| ").append(prompt.getDescription())
                        .append(" | `").append(prompt.promptFilename()).append("` |\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Builds one file's {@code summary.md}: calculation/chronology validity, structural
     * highlights, and either a table of verdicts (chat model active) or a pointer to each
     * prompt file for manual use (no chat model).
     */
    private static String buildFileSummary(String filename, ProcessingResult result) {
        final var sb = new StringBuilder();
        sb.append("# Compprov Analytics Report: ").append(filename).append("\n\n");
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
        if (chatModel == null) {
            sb.append("No chat model is configured, so the fraud-pattern prompts below were generated ")
                    .append("but not sent to an LLM. You can either:\n\n")
                    .append("- copy each prompt file listed below into the user interface of an LLM of your choice ")
                    .append("and review the response manually, or\n")
                    .append("- re-run this tool with `--plugin=<path-to-plugin-jar>` pointing to a plugin that ")
                    .append("supplies a `dev.langchain4j.model.chat.ChatModel` implementation, so the prompts are ")
                    .append("sent automatically and verdicts appear in this report.\n\n");
            sb.append("| Prompt | Prompt file |\n|---|---|\n");
            for (var prompt : activePrompts) {
                sb.append("| ").append(prompt.getDescription())
                        .append(" | `").append(prompt.promptFilename()).append("` |\n");
            }
            sb.append("\n");
        } else {
            sb.append("| Prompt | Verdict | Confidence | Report |\n|---|---|---|---|\n");
            for (var prompt : activePrompts) {
                final var llmResult = result.getLlmResults().get(prompt);
                if (llmResult == null) {
                    sb.append("| ").append(prompt.getDescription()).append(" | — | — | — |\n");
                } else {
                    sb.append("| ").append(prompt.getDescription())
                            .append(" | ").append(llmResult.verdict())
                            .append(" | ").append(llmResult.confidenceScore())
                            .append(" | `").append(prompt.markdownResultFilename()).append("` |\n");
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Loads {@code pluginAddress} into a child {@link URLClassLoader} and applies every
     * {@link EnvironmentCustomizer} and {@link ChatModel} it declares via
     * {@code META-INF/services}. A later plugin's {@code ChatModel} silently replaces an earlier
     * one; {@link EnvironmentCustomizer}s are cumulative.
     *
     * @param pluginAddress path to the plugin jar, as passed to {@code --plugin=}
     * @throws IOException if the jar doesn't exist or can't be read
     */
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

        if (!found) {
            LOGGER.severe("Warning: Nor EnvironmentCustomizer not ChatModel implementations found in " + pluginAddress);
        }
    }

    /**
     * Runs every deterministic structural check on one CPG snapshot, writing the offending
     * variables/operations for each hit to its own JSON file under {@code <prepath>/<fileAddress's
     * name>/}, then — if a chat model is configured — runs the snapshot through all five
     * {@link Prompt}s (see {@link #processPrompt}).
     *
     * @param prepath     the timestamped run directory (see {@link #main})
     * @param fileAddress path to the CPG snapshot JSON to process
     * @return the accumulated highlights, validity flags, and LLM verdicts for this file
     */
    private static ProcessingResult processFile(String prepath, int fileId, String fileAddress) {

        //read file
        final var file = new File(fileAddress);
        if ((!file.exists()) || (!file.isFile())) {
            throw new IllegalArgumentException("Unable to find file: " + fileAddress);
        }

        //parse snapshot
        Snapshot snapshot;
        String snapshotStr;
        try {
            snapshotStr = new String(Files.readAllBytes(file.toPath()));
            snapshot = ENV.fromJson(snapshotStr);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read file: " + fileAddress, e);
        }

        //prepare output dirs
        final var snapshotNavigator = new SnapshotNavigator(snapshot);
        var path = prepath + "/" + fileId + "_" + file.getName();
        new File(path).mkdirs();
        ProcessingResult result = new ProcessingResult(path);

        //validate calculation results
        final var invalidVars = new HashMap<String, Snapshot.Variable>();
        final var invalidOps = new ArrayList<Snapshot.Operation>();
        var variables = snapshot.variables();
        var computedVariables = ENV.compute(snapshot).snapshot().variables();
        for (int i = 0; i < variables.size(); i++) {
            var v = variables.get(i);
            var cv = computedVariables.get(i);
            if (!v.value().equals(cv.value())) {
                result.addHighlight("Invalid variable value detected. Expected: " + v.value() + ". Computed: " + cv.value() + ". Variable id: " + v.track().getId());
                result.invalidateCalculation();
                invalidVars.put(v.track().getId(), v);
                var operation = snapshotNavigator.producedBy(v.track().getId()).orElseThrow();
                invalidOps.add(operation);
                operation.arguments().forEach(arg -> invalidVars.put(arg.value(), snapshotNavigator.variable(arg.value()).orElseThrow()));
            }
        }
        save(serialize(new Snapshot(
                        Descriptor.descriptor("violated computations"),
                        invalidVars.values()
                                .stream()
                                .sorted(Comparator.comparingInt(v -> v.track().getNumericId()))
                                .toList(),
                        invalidOps
                                .stream()
                                .sorted(Comparator.comparingInt(v -> v.track().getNumericId()))
                                .toList())),
                path + "/violated-computations.json");

        //roots
        final var roots = snapshotNavigator.roots();
        save(serialize(roots), path + "/roots.json");

        //leaves
        final var leaves = snapshotNavigator.leaves();
        save(serialize(leaves), path + "/leaves.json");
        final var duplicateNameGroups = new ArrayList<String>();
        if (leaves.size() > 1) {
            result.addHighlight("Multiple leaves detected: " + leaves.size() + " please make sure the CPG has no gaps and substitutions");
            for (var leaf : leaves) {
                final var name = leaf.track().getDescriptor().getName();
                final var vars = snapshotNavigator.findVariables(name);
                if (vars.size() > 1) {
                    //variable with the same name is found gap is possible
                    result.addHighlight("There are variables with the same name as a leaf: "
                            + name + " variables count: " + vars.size());
                    final var otherIds = vars.stream()
                            .map(v -> v.track().getId())
                            .filter(id -> !id.equals(leaf.track().getId()))
                            .collect(java.util.stream.Collectors.joining(", "));
                    duplicateNameGroups.add("%s (\"%s\") shares its name with: [%s]".formatted(leaf.track().getId(), name, otherIds));
                }
            }
        }
        final var duplicateNameLeafIds = duplicateNameGroups.isEmpty()
                ? "[] (none detected)"
                : String.join("; ", duplicateNameGroups);

        //unused vars
        final var unused = snapshotNavigator.unused();
        save(serialize(unused), path + "/unused.json");
        if (!unused.isEmpty()) {
            result.addHighlight("Unused roots detected");
        }

        //math contexts and multi-usage
        final var mathContexts = new ArrayList<Snapshot.Variable>();
        final var multiused = new ArrayList<Snapshot.Variable>();
        for (var variable : snapshotNavigator.variables()) {
            if (variable.track().getValueClass().equals(MathContext.class.getName())) {
                //math context could be used multiple times
                mathContexts.add(variable);
                continue;
            }
            final var producedVariables = snapshotNavigator.produces(variable.track().getId());
            if (producedVariables.size() > 1) {
                //might be caused by double counting
                multiused.add(variable);
            }
        }

        save(serialize(multiused), path + "/multi-used.json");
        if (!multiused.isEmpty()) {
            result.addHighlight("Multi-used variables detected, please check CPG for double-counting");
        }

        save(serialize(mathContexts), path + "/math-contexts.json");
        if (mathContexts.size() > 1) {
            result.addHighlight("Multiple math contexts detected, please check CPG for rounding fraud");
        }

        //scaling
        final var scalingVars = new HashMap<String, Snapshot.Variable>();
        final var scalingOps = new ArrayList<Snapshot.Operation>();
        for (var operation : snapshotNavigator.operations()) {
            if ("setScale".equals(operation.track().getDescriptor().getName())) {
                scalingOps.add(operation);
                scalingVars.put(operation.resultId(), snapshotNavigator.variable(operation.resultId()).orElseThrow());
                operation.arguments().forEach(arg -> scalingVars.put(arg.value(), snapshotNavigator.variable(arg.value()).orElseThrow()));
            }
        }
        save(serialize(new Snapshot(
                        Descriptor.descriptor("scaling operations set"),
                        scalingVars.values()
                                .stream()
                                .sorted(Comparator.comparingInt(v -> v.track().getNumericId()))
                                .toList(), scalingOps)),
                path + "/scaling-operations.json");
        if (!scalingOps.isEmpty()) {
            result.addHighlight("Scaling operation is used, please check CPG for rounding fraud");
        }

        //chronology
        final var chronoVars = new HashMap<String, Snapshot.Variable>();
        final var chronoOps = new HashMap<String, Snapshot.Operation>();
        var date = ZonedDateTime.ofInstant(Instant.ofEpochMilli(0), ZoneId.of("UTC"));
        for (var operation : snapshotNavigator.operations()) {
            if (operation.track().getStartedAt().isBefore(date)) {
                result.addHighlight("Broken chronology detected. Operation " + operation.track().getId() + " started at is before previous operation");
                chronoOps.put(operation.track().getId(), operation);
                chronoVars.put(operation.resultId(), snapshotNavigator.variable(operation.resultId()).orElseThrow());
                operation.arguments().forEach(arg -> chronoVars.put(arg.value(), snapshotNavigator.variable(arg.value()).orElseThrow()));
                result.invalidateChronology();
            }
            date = operation.track().getStartedAt();

            for (var argument : operation.arguments()) {
                final var variable = snapshotNavigator.variable(argument.variableId()).orElseThrow();
                if (date.isBefore(variable.track().getCreatedAt())) {
                    result.addHighlight("Broken chronology detected. Variable " + variable.track().getId() + " created after operation " + operation.track().getId() + " started");
                    chronoOps.put(operation.track().getId(), operation);
                    chronoVars.put(operation.resultId(), snapshotNavigator.variable(operation.resultId()).orElseThrow());
                    operation.arguments().forEach(arg -> chronoVars.put(arg.value(), snapshotNavigator.variable(arg.value()).orElseThrow()));
                    result.invalidateChronology();
                }
            }

            if (operation.track().getFinishedAt().isBefore(date)) {
                result.addHighlight("Broken chronology detected. Operation " + operation.track().getId() + " finished at is before operation started");
                chronoOps.put(operation.track().getId(), operation);
                chronoVars.put(operation.resultId(), snapshotNavigator.variable(operation.resultId()).orElseThrow());
                operation.arguments().forEach(arg -> chronoVars.put(arg.value(), snapshotNavigator.variable(arg.value()).orElseThrow()));
                result.invalidateChronology();
            }
            date = operation.track().getFinishedAt();

            final var variable = snapshotNavigator.variable(operation.resultId()).orElseThrow();
            if (date.isAfter(variable.track().getCreatedAt())) {
                result.addHighlight("Broken chronology detected. Operation " + operation.track().getId() + " finished at is after result created");
                chronoOps.put(operation.track().getId(), operation);
                chronoVars.put(operation.resultId(), snapshotNavigator.variable(operation.resultId()).orElseThrow());
                operation.arguments().forEach(arg -> chronoVars.put(arg.value(), snapshotNavigator.variable(arg.value()).orElseThrow()));
                result.invalidateChronology();
            }
        }
        save(serialize(new Snapshot(
                        Descriptor.descriptor("violated chronology operations"),
                        chronoVars.values()
                                .stream()
                                .sorted(Comparator.comparingInt(v -> v.track().getNumericId()))
                                .toList(),
                        chronoOps.values()
                                .stream()
                                .sorted(Comparator.comparingInt(v -> v.track().getNumericId()))
                                .toList())),
                path + "/chrono-violations.json");

        //llm prompts
        final var rootIds = formatIdList(roots);
        final var leafIds = formatIdList(leaves);
        final var multiUsedIds = formatIdList(multiused);

        String sharedSystemText = null;
        if (chatModel != null) {
            try {
                sharedSystemText = Prompt.sharedJsonSystemPromptTemplate(templateOverrides.get(Prompt.sharedJsonSystemTemplateKey()), snapshotStr, rootIds, leafIds, multiUsedIds, duplicateNameLeafIds);
                save(sharedSystemText, path + "/" + Prompt.sharedJsonSystemPromptFilename());
            } catch (IOException e) {
                throw new IllegalStateException("Unable to create shared system prompt", e);
            }
        }

        for (int i = 0; i < activePrompts.size(); i++) {
            final var prompt = activePrompts.get(i);
            final var r = processPrompt(path, snapshotStr, prompt, sharedSystemText, rootIds, leafIds, multiUsedIds, duplicateNameLeafIds);
            LOGGER.info("Prompt %d of %d: %s. Verdict: %s(%.2f)".formatted(
                    i + 1,
                    activePrompts.size(),
                    prompt.getDescription(),
                    r.isEmpty() ? "-" : r.get().verdict(),
                    r.isEmpty() ? 0 : r.get().confidence_score()));
            r.ifPresent(promptProcessingResult -> result.addLlmResult(prompt, promptProcessingResult));
        }

        save(ENV.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result.getHighlights()), path + "/highlights.json");
        return result;
    }

    /**
     * Pretty-prints {@code data} via compprov-core's Jackson mapper.
     */
    private static String serialize(Object data) {
        try {
            return ENV.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (JacksonException e) {
            throw new IllegalStateException("Unable to serialize object: " + data, e);
        }
    }

    /**
     * Writes {@code data} to {@code address}, creating parent-less files and overwriting any existing content.
     */
    private static void save(String data, String address) {
        try {
            Files.writeString(new File(address).toPath(), data,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write file: " + address, e);
        }
    }

    /**
     * Formats a list of variables as a prompt-friendly {@code [id1, id2, ...]} string, or
     * {@code "[] (none detected)"} if empty — used for the root/leaf/multi-used ID lists
     * substituted into the prompt templates.
     */
    private static String formatIdList(java.util.List<Snapshot.Variable> variables) {
        if (variables.isEmpty()) {
            return "[] (none detected)";
        }
        return variables.stream()
                .map(v -> v.track().getId())
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    /**
     * Runs a single {@link Prompt} against one snapshot. With no chat model configured, this
     * only renders the standalone markdown prompt for manual use and returns empty. With a chat
     * model, it sends {@code sharedSystemText} and the prompt's own user template as separate
     * {@code SystemMessage}/{@code UserMessage}s (so the large, per-file-constant system content
     * can be cached across all five calls), then parses the JSON response — falling back to
     * {@link #tryExtractMarkdownedJson} / {@link #tryExtractJsonByFields} if the model didn't
     * return clean JSON — and renders the markdown result file.
     *
     * @return the parsed result, or empty if no chat model is configured, the response was
     * empty/unparseable, or the file couldn't be written
     */
    private static Optional<PromptProcessingResult> processPrompt(String path, String snapshotStr, Prompt prompt,
                                                                  String sharedSystemText, String rootIds, String leafIds,
                                                                  String multiUsedIds, String duplicateNameLeafIds) {
        if (chatModel == null) {
            try {
                final var promptText = prompt.markdownPromptTemplate(templateOverrides.get(prompt.markdownTemplateKey()), snapshotStr, rootIds, leafIds, multiUsedIds, duplicateNameLeafIds);
                save(promptText, path + "/" + prompt.promptFilename());
            } catch (IOException e) {
                throw new IllegalStateException("Unable to create prompt: " + prompt.getDescription(), e);
            }
            return Optional.empty();
        }

        String userText;
        try {
            userText = prompt.jsonUserPromptTemplate(templateOverrides.get(prompt.jsonUserTemplateKey()));
            save(userText, path + "/" + prompt.jsonUserPromptFilename());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create prompt: " + prompt.getDescription(), e);
        }

        try {
            if (intercallTimeoutMs > 0) {
                LOGGER.info("Intercall pause: " + prompt.getDescription());
                Thread.sleep(intercallTimeoutMs);
            }

            final var chatResponse = chatModel.chat(List.of(new SystemMessage(sharedSystemText), new UserMessage(userText)));
            final var finishReason = chatResponse.finishReason();
            final var resultStr = chatResponse.aiMessage() != null ? chatResponse.aiMessage().text() : null;

            if (resultStr == null || resultStr.isBlank()) {
                LOGGER.log(Level.SEVERE, "Empty response for prompt: " + prompt.getDescription()
                        + " (finishReason=" + finishReason + "). This usually means the model exhausted its "
                        + "output token budget (e.g. on internal reasoning) before producing any visible text -- "
                        + "consider raising maxTokens or asking for a more concise report.");
                return Optional.empty();
            }
            if (finishReason == FinishReason.LENGTH) {
                LOGGER.log(Level.WARNING, "Response for prompt: " + prompt.getDescription()
                        + " was truncated (finishReason=LENGTH) before completing -- raw partial response saved to "
                        + prompt.jsonResultFilename() + ", but it may not parse as valid JSON.");
            }

            save(resultStr, path + "/" + prompt.jsonResultFilename());

            PromptProcessingResult result;
            try {
                result = ENV.getMapper().readValue(resultStr, PromptProcessingResult.class);
            } catch (Throwable ex) {
                LOGGER.log(Level.WARNING, "Unable to parse response as JSON", ex);

                result = tryExtractMarkdownedJson(resultStr);
                if (result == null) {
                    result = tryExtractJsonByFields(resultStr);
                    if (result == null) {
                        LOGGER.log(Level.SEVERE, "Unable to re-parse response as JSON. Response saved into " + prompt.jsonResultFilename(), ex);
                        return Optional.empty();
                    }
                }
            }

            try (final var is = Main.class.getResourceAsStream("/templates/markdown_result.md")) {
                final var markdownResult = new String(is.readAllBytes())
                        .replace("$VERDICT$", result.verdict())
                        .replace("$SCORE$", Double.toString(result.confidence_score()))
                        .replace("$MARKDOWN$", result.markdown_report());
                save(markdownResult, path + "/" + prompt.markdownResultFilename());
            }
            return Optional.of(result);
        } catch (Throwable e) {
            throw new IllegalStateException("Unable to process prompt: " + prompt.getDescription(), e);
        }
    }

    /**
     * Last-resort fallback when {@code resultStr} isn't valid JSON at all: slices out just the
     * {@code verdict} and {@code confidence_score} fields by locating the {@code "verdict"} and
     * {@code "markdown_report"} keys textually and re-wrapping the fragment between them as a
     * small JSON object with a placeholder report. Recovers a verdict/score even when the
     * model's free-form response can't be parsed any other way, at the cost of losing the
     * detailed report (the raw response is still saved separately).
     *
     * @return the recovered result, or {@code null} if the fields can't be located or the
     * reconstructed fragment still isn't valid JSON
     */
    private static PromptProcessingResult tryExtractJsonByFields(String resultStr) {
        try {
            final var verdict = resultStr.indexOf("\"verdict\"");
            if (verdict < 0) {
                return null;
            }

            final var markdownreport = resultStr.indexOf("\"markdown_report\"");
            if (markdownreport < 0) {
                return null;
            }

            final var properJsonStr = "{ " + resultStr.substring(verdict, markdownreport) + "\"markdown_report\": \"failed to parse, see json output for details\" }";
            final var r = ENV.getMapper().readValue(properJsonStr, PromptProcessingResult.class);
            LOGGER.info("Processed output manually, extracted payload without detailed report, but raw response is saved");
            return r;
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * Fallback for when the model wraps its JSON response in a ```json fenced code block instead
     * of returning bare JSON (despite the prompt asking it not to) — extracts and parses just the
     * fenced content.
     *
     * @return the parsed result, or {@code null} if there's no fenced block or its content isn't
     * valid JSON
     */
    private static PromptProcessingResult tryExtractMarkdownedJson(String resultStr) {
        try {
            var start = resultStr.indexOf("```json");
            if (start < 0) {
                return null;
            }
            start += "```json".length();

            final var end = resultStr.lastIndexOf("```");
            if (end < 0) {
                return null;
            }
            if (start >= end) {
                return null;
            }

            final var r = ENV.getMapper().readValue(resultStr.substring(start, end), PromptProcessingResult.class);
            LOGGER.info("Processed output manually, extracted payload without losses");
            return r;
        } catch (Throwable throwable) {
            return null;
        }
    }
}
