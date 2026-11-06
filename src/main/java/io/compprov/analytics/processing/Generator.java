package io.compprov.analytics.processing;

import io.compprov.analytics.ai.Prompt;
import io.compprov.core.Snapshot;
import io.compprov.core.meta.Descriptor;
import io.compprov.tools.SnapshotNavigator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.math.MathContext;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;

import static io.compprov.analytics.Commons.ENV;
import static io.compprov.analytics.Commons.LOGGER;
import static io.compprov.analytics.Commons.read;
import static io.compprov.analytics.Commons.save;
import static io.compprov.analytics.Commons.serialize;


public class Generator {

    private static final List<String> PRUNED_FIELDS = List.of("createdAt", "startedAt", "finishedAt", "kind", "numericId");
    // fixed rather than platform-dependent, so prompts (and their token counts) are identical on every OS
    private static final String PRUNED_LINE_SEPARATOR = "\r\n";

    private final String workDir;
    private final PromptProcessingMode mode;
    private final List<Prompt> activePrompts;
    private final Map<String, String> templateOverrides;
    private final boolean minify;

    public Generator(String workDir, PromptProcessingMode mode, List<Prompt> activePrompts, Map<String, String> templateOverrides, boolean minify) {
        this.workDir = workDir;
        this.mode = mode;
        this.activePrompts = activePrompts;
        this.templateOverrides = templateOverrides;
        this.minify = minify;
    }

    public List<ProcessingResult> generate(List<String> cpgFiles) {
        final var result = new ArrayList<ProcessingResult>();
        for (int i = 0; i < cpgFiles.size(); i++) {
            final var file = cpgFiles.get(i);
            final var fileId = i + 1;

            LOGGER.info("Pre-processing file %d of %d: %s".formatted(fileId, cpgFiles.size(), file));
            result.add(preProcessFile(fileId, file));
        }
        return result;
    }

    private ProcessingResult preProcessFile(int fileId, String fileAddress) {

        //read file
        final var file = new File(fileAddress);
        if ((!file.exists()) || (!file.isFile())) {
            throw new IllegalArgumentException("Unable to find file: " + fileAddress);
        }

        //parse snapshot
        final Snapshot snapshot;
        try {
            snapshot = ENV.fromJson(read(fileAddress));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unable to read file: " + fileAddress, e);
        }
        final var prunedSnapshotStr = minify ? pruneAndMinify(snapshot) : prune(snapshot);

        //prepare output dirs
        final var snapshotNavigator = new SnapshotNavigator(snapshot);
        var path = workDir + "/" + fileId + "_" + file.getName();
        new File(path).mkdirs();
        ProcessingResult result = new ProcessingResult(path, fileAddress);

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
                operation.arguments().forEach(arg -> invalidVars.put(arg.variableId(), snapshotNavigator.variable(arg.variableId()).orElseThrow()));
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
                            .collect(Collectors.joining(", "));
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

        //operations consuming the same variable more than once, e.g. add(x, x); invisible to the multi-usage check above
        final var repeatedArgOps = new ArrayList<Snapshot.Operation>();
        final var repeatedArgVars = new HashSet<Snapshot.Variable>();
        final var repeatedArgGroups = new ArrayList<String>();
        for (var operation : snapshotNavigator.operations()) {
            final var seen = new HashSet<String>();
            final var repeated = new LinkedHashSet<String>();
            for (var argument : operation.arguments()) {
                if (!seen.add(argument.variableId())) {
                    repeated.add(argument.variableId());
                }
            }
            if (!repeated.isEmpty()) {
                repeatedArgOps.add(operation);
                repeatedArgGroups.add("%s (\"%s\") consumes [%s] more than once".formatted(
                        operation.track().getId(), operation.track().getDescriptor().getName(), String.join(", ", repeated)));
                operation.arguments().forEach(argId -> repeatedArgVars.add(snapshotNavigator.variable(argId.variableId()).orElseThrow()));
                repeatedArgVars.add(snapshotNavigator.variable(operation.resultId()).orElseThrow());
            }
        }
        save(serialize(new Snapshot(
                        Descriptor.descriptor("operations consuming the same variable more than once"),
                        repeatedArgVars
                                .stream()
                                .sorted(Comparator.comparingInt(v -> v.track().getNumericId()))
                                .toList(), repeatedArgOps)),
                path + "/repeated-arguments.json");
        if (!repeatedArgOps.isEmpty()) {
            result.addHighlight("Operations consuming the same variable more than once detected, please check CPG for double-counting");
        }
        final var repeatedArgumentOpIds = repeatedArgGroups.isEmpty()
                ? "[] (none detected)"
                : String.join("; ", repeatedArgGroups);

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
                collectOperationVariables(snapshotNavigator, operation, scalingVars);
            }
            if ("plus".equals(operation.track().getDescriptor().getName())) {
                final var varId = operation.arguments().get(0).variableId();
                final var wrappedVar = snapshotNavigator.variable(varId);
                if (wrappedVar.filter(v -> v.track().getValueClass().equalsIgnoreCase("java.math.BigDecimal")).isPresent()) {
                    scalingOps.add(operation);
                    collectOperationVariables(snapshotNavigator, operation, scalingVars);
                }
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
            final var opId = operation.track().getId();
            if (operation.track().getStartedAt().isBefore(date)) {
                reportChronologyViolation(result, snapshotNavigator, operation, chronoOps, chronoVars,
                        "Operation " + opId + " started at is before previous operation");
            }
            date = operation.track().getStartedAt();

            for (var argument : operation.arguments()) {
                final var variable = snapshotNavigator.variable(argument.variableId()).orElseThrow();
                if (date.isBefore(variable.track().getCreatedAt())) {
                    reportChronologyViolation(result, snapshotNavigator, operation, chronoOps, chronoVars,
                            "Variable " + variable.track().getId() + " created after operation " + opId + " started");
                }
            }

            if (operation.track().getFinishedAt().isBefore(date)) {
                reportChronologyViolation(result, snapshotNavigator, operation, chronoOps, chronoVars,
                        "Operation " + opId + " finished at is before operation started");
            }
            date = operation.track().getFinishedAt();

            final var variable = snapshotNavigator.variable(operation.resultId()).orElseThrow();
            if (date.isAfter(variable.track().getCreatedAt())) {
                reportChronologyViolation(result, snapshotNavigator, operation, chronoOps, chronoVars,
                        "Operation " + opId + " finished at is after result created");
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

        // system prompts keyed by file name: prompts with the same system prompt name share one
        final var systemTexts = new HashSet<String>();
        if (mode.isJsonPrompts()) {
            for (var prompt : activePrompts) {
                final var filename = prompt.jsonSystemPromptFilename();
                if (systemTexts.contains(filename)) {
                    continue;
                }
                try {
                    final var systemText = prompt.jsonSystemPromptTemplate(templateOverrides.get(prompt.jsonSystemTemplateKey()), prunedSnapshotStr, rootIds, leafIds, multiUsedIds, duplicateNameLeafIds, repeatedArgumentOpIds);
                    save(systemText, path + "/" + filename);
                    systemTexts.add(filename);
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to create system prompt: " + filename, e);
                }
            }
        }

        for (var prompt : activePrompts) {
            generatePrompt(path, prunedSnapshotStr, prompt, rootIds, leafIds, multiUsedIds, duplicateNameLeafIds, repeatedArgumentOpIds);
        }

        save(serialize(result.getHighlights()), path + "/highlights.json");
        return result;
    }

    private void generatePrompt(String path, String snapshotStr, Prompt prompt,
                                String rootIds, String leafIds,
                                String multiUsedIds, String duplicateNameLeafIds,
                                String repeatedArgumentOpIds) {
        try {
            if (mode.isJsonPrompts()) {
                final var userText = prompt.jsonUserPromptTemplate(templateOverrides.get(prompt.jsonUserTemplateKey()), snapshotStr, rootIds, leafIds, multiUsedIds, duplicateNameLeafIds, repeatedArgumentOpIds);
                save(userText, path + "/" + prompt.jsonUserPromptFilename());
            } else {
                final var promptText = prompt.markdownPromptTemplate(templateOverrides.get(prompt.markdownTemplateKey()), snapshotStr, rootIds, leafIds, multiUsedIds, duplicateNameLeafIds, repeatedArgumentOpIds);
                save(promptText, path + "/" + prompt.promptFilename());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create prompt: " + prompt.getDescription(), e);
        }
    }

    private static void reportChronologyViolation(ProcessingResult result, SnapshotNavigator navigator, Snapshot.Operation operation,
                                                  Map<String, Snapshot.Operation> chronoOps, Map<String, Snapshot.Variable> chronoVars,
                                                  String message) {
        result.addHighlight("Broken chronology detected. " + message);
        chronoOps.put(operation.track().getId(), operation);
        collectOperationVariables(navigator, operation, chronoVars);
        result.invalidateChronology();
    }

    /**
     * Adds the operation's result and argument variables to {@code target}.
     */
    private static void collectOperationVariables(SnapshotNavigator navigator, Snapshot.Operation operation, Map<String, Snapshot.Variable> target) {
        target.put(operation.resultId(), navigator.variable(operation.resultId()).orElseThrow());
        operation.arguments().forEach(arg -> target.put(arg.variableId(), navigator.variable(arg.variableId()).orElseThrow()));
    }

    private String formatIdList(List<Snapshot.Variable> variables) {
        if (variables.isEmpty()) {
            return "[] (none detected)";
        }
        return variables.stream()
                .map(v -> v.track().getId())
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String prune(Snapshot snapshot) {
        final var prettyString = ENV.toJson(snapshot);
        final var builder = new StringBuilder(prettyString.length());
        final var scanner = new Scanner(prettyString);

        while (scanner.hasNextLine()) {
            final var line = scanner.nextLine().trim();
            if (line.startsWith("\"createdAt\"")) {
                continue;
            }
            if (line.startsWith("\"startedAt\"")) {
                continue;
            }
            if (line.startsWith("\"finishedAt\"")) {
                continue;
            }
            if (line.startsWith("\"kind\"")) {
                continue;
            }
            if (line.startsWith("\"numericId\"")) {
                continue;
            }

            builder.append(line).append(PRUNED_LINE_SEPARATOR);
        }
        return builder.toString();
    }

    /**
     * Same fields as {@link #prune(Snapshot)}, removed from the JSON tree and written without indentation or line breaks.
     */
    private String pruneAndMinify(Snapshot snapshot) {
        final JsonNode tree = ENV.getMapper().valueToTree(snapshot);
        removePrunedFields(tree);
        return ENV.getMapper().writeValueAsString(tree);
    }

    private static void removePrunedFields(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            objectNode.remove(PRUNED_FIELDS);
        }
        for (var child : node) {
            removePrunedFields(child);
        }
    }
}
