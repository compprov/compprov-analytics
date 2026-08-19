package io.compprov.analytics.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The five fraud-pattern prompts run against every CPG snapshot, each backed by two resource
 * templates under {@code src/main/resources/prompts/}:
 * <ul>
 *   <li>{@code markdown/<file>_prompt.md} — a single standalone prompt (role, CPG spec, attack
 *       definition, invariants, response format all in one document) for the no-chat-model path,
 *       where the rendered file is meant to be pasted into an LLM UI by hand.</li>
 *   <li>{@code json/<file>_user.md} — the attack-specific half of the chat-model path: role and
 *       CPG spec live once in the shared {@code json/shared_system.md} (sent as a single
 *       {@code SystemMessage} reused, and ideally cached, across all five prompts for a file),
 *       while this file supplies the per-attack objective, invariants, and expected verdict
 *       values as the {@code UserMessage}.</li>
 * </ul>
 * Both templates substitute the same five placeholders — {@code $CPG$},
 * {@code $ROOT_VARIABLE_IDS$}, {@code $LEAF_VARIABLE_IDS$}, {@code $MULTIUSED_VARIABLE_IDS$},
 * {@code $DUPLICATE_NAME_LEAF_IDS$} — computed once per snapshot in {@code Main.processFile}.
 */
public enum Prompt {

    CALCULATION_OMISSION("Calculation omission", "calculation_omission"),
    LINEAGE_DISCONNECTION("Lineage disconnection", "lineage_disconnection_and_context_substitution"),
    PRECISION_TAMPERING("Precision tampering", "precision_tampering"),
    SEMANTIC_VIOLATION("Semantic violation", "semantic_type_and_context_cast_attack"),
    DOUBLE_COUNTING("Double counting", "topological_accumulation_fraud_via_double_counting");

    private final String description;
    private final String file;

    Prompt(String description, String file) {
        this.description = description;
        this.file = file;
    }

    /**
     * @return the human-readable name shown in reports and log messages, e.g. {@code "Calculation omission"}
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return the short identifier used for {@code --llmTemplates=}, e.g. {@code "calculation_omission"}
     */
    public String getTemplateName() {
        return file;
    }

    /**
     * Looks up a {@link Prompt} by its {@code --llmTemplates=} identifier (the same name as
     * {@link #getTemplateName()}, e.g. {@code "calculation_omission"}).
     *
     * @throws IllegalArgumentException if no prompt matches {@code templateName}
     */
    public static Prompt fromTemplateName(String templateName) {
        for (var prompt : values()) {
            if (prompt.file.equals(templateName)) {
                return prompt;
            }
        }
        throw new IllegalArgumentException("Unknown LLM template name: " + templateName
                + ". Valid names are: " + String.join(", ", java.util.Arrays.stream(values()).map(Prompt::getTemplateName).toList()));
    }

    /**
     * @return the CLI override key for this prompt's standalone markdown template, e.g.
     * {@code "calculation_omission_prompt"} — matches the {@code --<key>=<path>} argument that
     * overrides {@code markdown/<file>_prompt.md}
     */
    public String markdownTemplateKey() {
        return file + "_prompt";
    }

    /**
     * @return the CLI override key for this prompt's JSON user template, e.g.
     * {@code "calculation_omission_user"} — matches the {@code --<key>=<path>} argument that
     * overrides {@code json/<file>_user.md}
     */
    public String jsonUserTemplateKey() {
        return file + "_user";
    }

    /**
     * @return the CLI override key for the shared JSON system template — matches the
     * {@code --shared_system=<path>} argument that overrides {@code json/shared_system.md}
     */
    public static String sharedJsonSystemTemplateKey() {
        return "shared_system";
    }

    /**
     * Reads a raw template: from {@code overridePath} on disk if given, otherwise from
     * {@code resourcePath} on the classpath.
     */
    private static String readTemplate(String overridePath, String resourcePath, String errorContext) throws IOException {
        try {
            if (overridePath != null) {
                return Files.readString(Path.of(overridePath));
            }
            try (final var is = Prompt.class.getResourceAsStream(resourcePath)) {
                return new String(is.readAllBytes());
            }
        } catch (Throwable e) {
            throw new IOException("Unable to read " + errorContext, e);
        }
    }

    /**
     * Renders this prompt's standalone markdown template (used when no chat model is configured)
     * with the given snapshot and structural reference data substituted in.
     *
     * @param overridePath if non-null, read the template from this path instead of the bundled
     *                     {@code markdown/<file>_prompt.md} resource (see {@link #markdownTemplateKey()})
     */
    public String markdownPromptTemplate(String overridePath, String cpg, String rootVariableIds, String leafVariableIds, String multiUsedVariableIds, String duplicateNameLeafIds) throws IOException {
        return readTemplate(overridePath, "/prompts/markdown/" + file + "_prompt.md", file + " markdown template")
                .replace("$CPG$", cpg)
                .replace("$ROOT_VARIABLE_IDS$", rootVariableIds)
                .replace("$LEAF_VARIABLE_IDS$", leafVariableIds)
                .replace("$MULTIUSED_VARIABLE_IDS$", multiUsedVariableIds)
                .replace("$DUPLICATE_NAME_LEAF_IDS$", duplicateNameLeafIds);
    }

    /**
     * Renders the shared system prompt (role, CPG spec, structural reference data, audit
     * discipline, response format — identical for all five {@link Prompt} values on a given
     * snapshot) with the snapshot and structural reference data substituted in. Static because
     * it's not specific to any one {@link Prompt}; called once per snapshot and reused as the
     * {@code SystemMessage} for every prompt's chat call.
     *
     * @param overridePath if non-null, read the template from this path instead of the bundled
     *                     {@code json/shared_system.md} resource (see {@link #sharedJsonSystemTemplateKey()})
     */
    public static String sharedJsonSystemPromptTemplate(String overridePath, String cpg, String rootVariableIds, String leafVariableIds, String multiUsedVariableIds, String duplicateNameLeafIds) throws IOException {
        return readTemplate(overridePath, "/prompts/json/shared_system.md", "shared json system template")
                .replace("$CPG$", cpg)
                .replace("$ROOT_VARIABLE_IDS$", rootVariableIds)
                .replace("$LEAF_VARIABLE_IDS$", leafVariableIds)
                .replace("$MULTIUSED_VARIABLE_IDS$", multiUsedVariableIds)
                .replace("$DUPLICATE_NAME_LEAF_IDS$", duplicateNameLeafIds);
    }

    /**
     * Renders this prompt's attack-specific user template (objective, attack definition,
     * invariants, expected verdict values). Unlike {@link #markdownPromptTemplate} and
     * {@link #sharedJsonSystemPromptTemplate}, this template has no placeholders of its own — all
     * snapshot-specific data lives in the shared system prompt instead.
     *
     * @param overridePath if non-null, read the template from this path instead of the bundled
     *                     {@code json/<file>_user.md} resource (see {@link #jsonUserTemplateKey()})
     */
    public String jsonUserPromptTemplate(String overridePath) throws IOException {
        return readTemplate(overridePath, "/prompts/json/" + file + "_user.md", file + " json user template");
    }

    /**
     * @return the filename this prompt's raw JSON chat-model response is saved under
     */
    public String jsonResultFilename() {
        return file + "_result.json";
    }

    /**
     * @return the filename this prompt's rendered markdown result (verdict + report) is saved under
     */
    public String markdownResultFilename() {
        return file + "_result.md";
    }

    /**
     * @return the filename this prompt's standalone markdown template is saved under (no-chat-model path)
     */
    public String promptFilename() {
        return file + "_prompt.md";
    }

    /**
     * @return the filename this prompt's rendered JSON user template is saved under (chat-model path)
     */
    public String jsonUserPromptFilename() {
        return file + "_user_prompt.md";
    }

    /**
     * @return the filename the shared JSON system prompt is saved under — one per snapshot, not per {@link Prompt}
     */
    public static String sharedJsonSystemPromptFilename() {
        return "system_prompt.md";
    }
}
