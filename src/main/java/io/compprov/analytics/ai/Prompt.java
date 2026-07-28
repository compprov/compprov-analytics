package io.compprov.analytics.ai;

import java.io.IOException;

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

    /** @return the human-readable name shown in reports and log messages, e.g. {@code "Calculation omission"} */
    public String getDescription() {
        return description;
    }

    /**
     * Renders this prompt's standalone markdown template (used when no chat model is configured)
     * with the given snapshot and structural reference data substituted in.
     */
    public String markdownPromptTemplate(String cpg, String rootVariableIds, String leafVariableIds, String multiUsedVariableIds, String duplicateNameLeafIds) throws IOException {
        try {
            return new String(Prompt.class.getResourceAsStream("/prompts/markdown/" + file + "_prompt.md").readAllBytes())
                    .replace("$CPG$", cpg)
                    .replace("$ROOT_VARIABLE_IDS$", rootVariableIds)
                    .replace("$LEAF_VARIABLE_IDS$", leafVariableIds)
                    .replace("$MULTIUSED_VARIABLE_IDS$", multiUsedVariableIds)
                    .replace("$DUPLICATE_NAME_LEAF_IDS$", duplicateNameLeafIds);
        } catch (Throwable e) {
            throw new IOException("Unable to read " + file + " markdown template", e);
        }
    }

    /**
     * Renders the shared system prompt (role, CPG spec, structural reference data, audit
     * discipline, response format — identical for all five {@link Prompt} values on a given
     * snapshot) with the snapshot and structural reference data substituted in. Static because
     * it's not specific to any one {@link Prompt}; called once per snapshot and reused as the
     * {@code SystemMessage} for every prompt's chat call.
     */
    public static String sharedJsonSystemPromptTemplate(String cpg, String rootVariableIds, String leafVariableIds, String multiUsedVariableIds, String duplicateNameLeafIds) throws IOException {
        try {
            return new String(Prompt.class.getResourceAsStream("/prompts/json/shared_system.md").readAllBytes())
                    .replace("$CPG$", cpg)
                    .replace("$ROOT_VARIABLE_IDS$", rootVariableIds)
                    .replace("$LEAF_VARIABLE_IDS$", leafVariableIds)
                    .replace("$MULTIUSED_VARIABLE_IDS$", multiUsedVariableIds)
                    .replace("$DUPLICATE_NAME_LEAF_IDS$", duplicateNameLeafIds);
        } catch (Throwable e) {
            throw new IOException("Unable to read shared json system template", e);
        }
    }

    /**
     * Renders this prompt's attack-specific user template (objective, attack definition,
     * invariants, expected verdict values). Unlike {@link #markdownPromptTemplate} and
     * {@link #sharedJsonSystemPromptTemplate}, this template has no placeholders of its own — all
     * snapshot-specific data lives in the shared system prompt instead.
     */
    public String jsonUserPromptTemplate() throws IOException {
        try {
            return new String(Prompt.class.getResourceAsStream("/prompts/json/" + file + "_user.md").readAllBytes());
        } catch (Throwable e) {
            throw new IOException("Unable to read " + file + " json user template", e);
        }
    }

    /** @return the filename this prompt's raw JSON chat-model response is saved under */
    public String jsonResultFilename() {
        return file + "_result.json";
    }

    /** @return the filename this prompt's rendered markdown result (verdict + report) is saved under */
    public String markdownResultFilename() {
        return file + "_result.md";
    }

    /** @return the filename this prompt's standalone markdown template is saved under (no-chat-model path) */
    public String promptFilename() {
        return file + "_prompt.md";
    }

    /** @return the filename this prompt's rendered JSON user template is saved under (chat-model path) */
    public String jsonUserPromptFilename() {
        return file + "_user_prompt.md";
    }

    /** @return the filename the shared JSON system prompt is saved under — one per snapshot, not per {@link Prompt} */
    public static String sharedJsonSystemPromptFilename() {
        return "system_prompt.md";
    }
}
