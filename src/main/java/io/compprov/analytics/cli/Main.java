package io.compprov.analytics.cli;

import dev.langchain4j.model.chat.ChatModel;
import io.compprov.analytics.ProcessingResult;
import io.compprov.analytics.ai.Prompt;
import io.compprov.analytics.ai.PromptProcessingResult;
import io.compprov.core.ComputationEnvironment;
import io.compprov.core.DefaultComputationEnvironment;
import io.compprov.core.EnvironmentCustomizer;
import io.compprov.core.Snapshot;
import io.compprov.core.meta.Descriptor;
import io.compprov.tools.SnapshotNavigator;
import tools.jackson.core.JacksonException;

import java.io.File;
import java.io.IOException;
import java.math.MathContext;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {

    private static final Logger LOGGER = Logger.getLogger("analytics-main");
    private static final ComputationEnvironment ENV = DefaultComputationEnvironment.create();
    private static ChatModel chatModel = null;
    private static boolean executePrompts = true;
    private static int intercallTimeoutMs = 0;

    public static void main(String[] args) {

        //no args
        if (args.length == 0) {
            LOGGER.severe("""
                    Usage: java -jar compprov-analytics.jar --cpgpath=<path-to-cpg-file> [--cpgpath=<path-to-cpg-file> ...] [--plugin=<path-to-plugin-jar>] [--intercallTimeoutMs=<ms>]
                      --cpgpath=<path>            Path to a CPG JSON file to analyze. Repeatable; at least one is required.
                      --plugin=<path>             Path to a plugin JAR providing EnvironmentCustomizer/ChatModel implementations. Optional, repeatable.
                      --executePrompts=true/false Default true. False will skip prompt execution even if ChatModel is set.
                      --intercallTimeoutMs=<ms>   Pause (in milliseconds) before each chat model call. Optional, defaults to 0.""");

            System.exit(1);
        }

        //plugins
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
        }
        if (!executePrompts) {
            chatModel = null;
        }

        //files
        Map<String, ProcessingResult> result = new HashMap<>();
        for (var arg : args) {
            if (arg.startsWith("--cpgpath=")) {
                try {
                    String filename = arg.substring("--cpgpath=".length());
                    var r = processFile(filename);
                    result.put(filename, r);
                    save(buildFileSummary(filename, r), "out/" + new File(filename).getName() + "/summary.md");
                } catch (Throwable th) {
                    LOGGER.log(Level.SEVERE, "Unable to process file: " + arg, th);
                }
            }
        }

        //aggregate results for all files
        new File("out").mkdirs();
        save(aggregateResults(result), "out/summary.md");
    }

    private static String aggregateResults(Map<String, ProcessingResult> results) {
        final var sb = new StringBuilder();
        sb.append("# Compprov Analytics Summary\n\n");
        sb.append("Generated: ").append(ZonedDateTime.now(ZoneId.of("UTC"))).append("\n\n");

        if (results.isEmpty()) {
            sb.append("No CPG files were processed.\n");
            return sb.toString();
        }

        sb.append("## Overview\n\n");
        sb.append("| File | Calculation | Highlights |");
        for (var prompt : Prompt.values()) {
            sb.append(" ").append(prompt.getDescription()).append(" |");
        }
        sb.append("\n|---|---|---|");
        for (var ignored : Prompt.values()) {
            sb.append("---|");
        }
        sb.append("\n");

        for (var entry : results.entrySet()) {
            final var file = entry.getKey();
            final var result = entry.getValue();
            sb.append("| ").append(file).append(" | ")
                    .append(result.isValidCalculation() ? "✅ Valid" : "❌ Invalid").append(" | ")
                    .append(result.getHighlights().size()).append(" |");
            for (var prompt : Prompt.values()) {
                final var llmResult = result.getLlmResults().get(prompt);
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
            if (entry.getValue().getHighlights().isEmpty()) {
                continue;
            }
            sb.append("### ").append(entry.getKey()).append("\n\n");
            for (var highlight : entry.getValue().getHighlights()) {
                sb.append("- ").append(highlight).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private static String buildFileSummary(String filename, ProcessingResult result) {
        final var sb = new StringBuilder();
        sb.append("# Compprov Analytics Report: ").append(filename).append("\n\n");
        sb.append("Generated: ").append(ZonedDateTime.now(ZoneId.of("UTC"))).append("\n\n");
        sb.append("**Calculation validity**: ")
                .append(result.isValidCalculation() ? "✅ Valid" : "❌ Invalid")
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
            for (var prompt : Prompt.values()) {
                sb.append("| ").append(prompt.getDescription())
                        .append(" | `").append(prompt.promptFilename()).append("` |\n");
            }
            sb.append("\n");
        } else {
            sb.append("| Prompt | Verdict | Confidence | Report |\n|---|---|---|---|\n");
            for (var prompt : Prompt.values()) {
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

    private static ProcessingResult processFile(String fileAddress) {

        ProcessingResult result = new ProcessingResult();

        //read file
        LOGGER.info("Processing %s file".formatted(fileAddress));
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
        var path = "out/" + file.getName();
        new File(path).mkdirs();

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
                var operation = snapshotNavigator.producedBy(v.track().getId()).get();
                invalidOps.add(operation);
                operation.arguments().forEach(arg -> invalidVars.put(arg.value().toString(), snapshotNavigator.variable(arg.value().toString()).get()));
            }
        }
        save(serialize(new Snapshot(
                        Descriptor.descriptor("violated computations"),
                        invalidVars.values()
                                .stream()
                                .sorted((v1, v2) -> Integer.compare(v1.track().getNumericId(), v2.track().getNumericId()))
                                .toList(),
                        invalidOps
                                .stream()
                                .sorted((v1, v2) -> Integer.compare(v1.track().getNumericId(), v2.track().getNumericId()))
                                .toList())),
                path + "/violated-computations.json");

        //roots
        final var roots = snapshotNavigator.roots();
        save(serialize(roots), path + "/roots.json");

        //leaves
        final var leaves = snapshotNavigator.leaves();
        save(serialize(leaves), path + "/leaves.json");
        if (leaves.size() > 1) {
            result.addHighlight("Multiple leaves detected: " + leaves.size() + " please make sure the CPG has no gaps and substitutions");
            for (var leaf : leaves) {
                final var vars = snapshotNavigator.findVariables(leaf.track().getDescriptor().getName());
                if (vars.size() > 1) {
                    //variable with the same name is found gap is possible
                    result.addHighlight("There are variables with the same name as a leaf: "
                            + leaf.track().getDescriptor().getName() + " variables count: " + vars.size());
                }
            }
        }

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
                scalingVars.put(operation.resultId(), snapshotNavigator.variable(operation.resultId()).get());
                operation.arguments().forEach(arg -> scalingVars.put(arg.value().toString(), snapshotNavigator.variable(arg.value().toString()).get()));
            }
        }
        save(serialize(new Snapshot(
                        Descriptor.descriptor("scaling operations set"),
                        scalingVars.values()
                                .stream()
                                .sorted((v1, v2) -> Integer.compare(v1.track().getNumericId(), v2.track().getNumericId()))
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
                chronoVars.put(operation.resultId(), snapshotNavigator.variable(operation.resultId()).get());
                operation.arguments().forEach(arg -> chronoVars.put(arg.value().toString(), snapshotNavigator.variable(arg.value().toString()).get()));
            }
            date = operation.track().getStartedAt();

            for (var argument : operation.arguments()) {
                final var variable = snapshotNavigator.variable(argument.variableId()).get();
                if (date.isBefore(variable.track().getCreatedAt())) {
                    result.addHighlight("Broken chronology detected. Variable " + variable.track().getId() + " created after operation " + operation.track().getId() + " started");
                    chronoOps.put(operation.track().getId(), operation);
                    chronoVars.put(operation.resultId(), snapshotNavigator.variable(operation.resultId()).get());
                    operation.arguments().forEach(arg -> chronoVars.put(arg.value().toString(), snapshotNavigator.variable(arg.value().toString()).get()));
                }
            }

            if (operation.track().getFinishedAt().isBefore(date)) {
                result.addHighlight("Broken chronology detected. Operation " + operation.track().getId() + " finished at is before operation started");
                chronoOps.put(operation.track().getId(), operation);
                chronoVars.put(operation.resultId(), snapshotNavigator.variable(operation.resultId()).get());
                operation.arguments().forEach(arg -> chronoVars.put(arg.value().toString(), snapshotNavigator.variable(arg.value().toString()).get()));
            }
            date = operation.track().getFinishedAt();

            final var variable = snapshotNavigator.variable(operation.resultId()).get();
            if (date.isBefore(variable.track().getCreatedAt())) {
                result.addHighlight("Broken chronology detected. Operation " + operation.track().getId() + " finished at is before result created");
                chronoOps.put(operation.track().getId(), operation);
                chronoVars.put(operation.resultId(), snapshotNavigator.variable(operation.resultId()).get());
                operation.arguments().forEach(arg -> chronoVars.put(arg.value().toString(), snapshotNavigator.variable(arg.value().toString()).get()));
            }
        }
        save(serialize(new Snapshot(
                        Descriptor.descriptor("violated chronology operations"),
                        chronoVars.values()
                                .stream()
                                .sorted((v1, v2) -> Integer.compare(v1.track().getNumericId(), v2.track().getNumericId()))
                                .toList(),
                        chronoOps.values()
                                .stream()
                                .sorted((v1, v2) -> Integer.compare(v1.track().getNumericId(), v2.track().getNumericId()))
                                .toList())),
                path + "/chrono-violations.json");

        //llm prompts
        for (var prompt : Prompt.values()) {
            final var r = processPrompt(path, snapshotStr, prompt);
            if (r.isPresent()) {
                result.addLlmResult(prompt, r.get());
            }
        }

        save(ENV.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result.getHighlights()), path + "/highlights.json");
        return result;
    }

    private static String serialize(Object data) {
        try {
            return ENV.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (JacksonException e) {
            throw new IllegalStateException("Unable to serialize object: " + data, e);
        }
    }

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

    private static Optional<PromptProcessingResult> processPrompt(String path, String snapshotStr, Prompt prompt) {

        String promptText;
        try {
            promptText = (chatModel == null) ? prompt.markdownPromptTemplate(snapshotStr) : prompt.jsonPromptTemplate(snapshotStr);
            save(promptText, path + "/" + prompt.promptFilename());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create prompt: " + prompt.getDescription(), e);
        }

        if (chatModel == null) {
            return Optional.empty();
        }

        try {
            if (intercallTimeoutMs > 0) {
                LOGGER.info("Intercall pause: " + prompt.getDescription());
                Thread.sleep(intercallTimeoutMs);
            }

            LOGGER.info("Processing prompt: " + prompt.getDescription());
            final var resultStr = chatModel.chat(promptText);
            save(resultStr, path + "/" + prompt.jsonResultFilename());

            PromptProcessingResult result;
            try {
                result = ENV.getMapper().readValue(resultStr, PromptProcessingResult.class);
            } catch (Throwable ex) {
                LOGGER.log(Level.WARNING, "Unable to parse response as JSON", ex);
                LOGGER.warning("trying to cleanup manually");

                int verdict = resultStr.indexOf("\"verdict\"");
                if (verdict < 0) {
                    LOGGER.log(Level.SEVERE, "Unable to re-parse response as JSON. Response saved into " + prompt.jsonResultFilename(), ex);
                    return Optional.empty();
                }

                int markdownreport = resultStr.indexOf("\"markdown_report\"");
                if (markdownreport < 0) {
                    LOGGER.log(Level.SEVERE, "Unable to re-parse response as JSON. Response saved into " + prompt.jsonResultFilename(), ex);
                    return Optional.empty();
                }

                String properJsonStr = "{ " + resultStr.substring(verdict, markdownreport) + "\"markdown_report\": \"failed to parse, see json output for details\" }";
                try {
                    result = ENV.getMapper().readValue(properJsonStr, PromptProcessingResult.class);
                } catch (Throwable ex2) {
                    LOGGER.log(Level.SEVERE, "Unable to re-parse response as JSON. Response saved into " + prompt.jsonResultFilename(), ex2);
                    return Optional.empty();
                }
            }

            final var markdownResult = new String(Main.class.getResourceAsStream("/templates/markdown_result.md").readAllBytes())
                    .replace("$VERDICT$", result.verdict())
                    .replace("$SCORE$", Double.toString(result.confidence_score()))
                    .replace("$MARKDOWN$", result.markdown_report());
            save(markdownResult, path + "/" + prompt.markdownResultFilename());

            return Optional.of(result);
        } catch (Throwable e) {
            throw new IllegalStateException("Unable to process prompt: " + prompt.getDescription(), e);
        }
    }
}
