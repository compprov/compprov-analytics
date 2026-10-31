package io.compprov.analytics.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public enum Prompt {

    CALCULATION_OMISSION("Calculation omission", "calculation_omission", "shared_system"),
    LINEAGE_DISCONNECTION("Lineage disconnection", "lineage_disconnection_and_context_substitution", "shared_system"),
    PRECISION_TAMPERING("Precision tampering", "precision_tampering", "shared_system"),
    SEMANTIC_VIOLATION("Semantic violation", "semantic_type_and_context_cast_attack", "shared_system"),
    DOUBLE_COUNTING("Double counting", "topological_accumulation_fraud_via_double_counting", "shared_system"),
    TOPOLOGICAL_FRAUD("Topological fraud", "topological_fraud", "shared_system"),
    /** Covers topological fraud, precision tampering and semantic violation in one call. */
    SINGLE("Single prompt", "single_prompt", "single_prompt_system");

    private final String description;
    private final String file;
    private final String systemPromptName;

    Prompt(String description, String file, String systemPromptName) {
        this.description = description;
        this.file = file;
        this.systemPromptName = systemPromptName;
    }

    public String getDescription() {
        return description;
    }

    public String getTemplateName() {
        return file;
    }

    public static Prompt fromTemplateName(String templateName) {
        for (var prompt : values()) {
            if (prompt.file.equals(templateName)) {
                return prompt;
            }
        }
        throw new IllegalArgumentException("Unknown LLM template name: " + templateName
                + ". Valid names are: " + String.join(", ", Arrays.stream(values()).map(Prompt::getTemplateName).toList()));
    }

    public String markdownTemplateKey() {
        return file + "_prompt";
    }

    public String jsonUserTemplateKey() {
        return file + "_user";
    }

    public String jsonSystemTemplateKey() {
        return systemPromptName;
    }

    private static String readTemplate(String overridePath, String resourcePath, String errorContext) throws IOException {
        try {
            if (overridePath != null) {
                return Files.readString(Path.of(overridePath));
            }
            try (final var is = Prompt.class.getResourceAsStream(resourcePath)) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Throwable e) {
            throw new IOException("Unable to read " + errorContext, e);
        }
    }

    private static String fill(String template, String cpg, String rootVariableIds, String leafVariableIds, String multiUsedVariableIds, String duplicateNameLeafIds, String repeatedArgumentOperationIds) {
        return template
                .replace("$CPG$", cpg)
                .replace("$ROOT_VARIABLE_IDS$", rootVariableIds)
                .replace("$LEAF_VARIABLE_IDS$", leafVariableIds)
                .replace("$MULTIUSED_VARIABLE_IDS$", multiUsedVariableIds)
                .replace("$DUPLICATE_NAME_LEAF_IDS$", duplicateNameLeafIds)
                .replace("$REPEATED_ARGUMENT_OPERATION_IDS$", repeatedArgumentOperationIds);
    }

    public String markdownPromptTemplate(String overridePath, String cpg, String rootVariableIds, String leafVariableIds, String multiUsedVariableIds, String duplicateNameLeafIds, String repeatedArgumentOperationIds) throws IOException {
        return fill(readTemplate(overridePath, "/prompts/markdown/" + file + "_prompt.md", file + " markdown template"),
                cpg, rootVariableIds, leafVariableIds, multiUsedVariableIds, duplicateNameLeafIds, repeatedArgumentOperationIds);
    }

    public String jsonSystemPromptTemplate(String overridePath, String cpg, String rootVariableIds, String leafVariableIds, String multiUsedVariableIds, String duplicateNameLeafIds, String repeatedArgumentOperationIds) throws IOException {
        return fill(readTemplate(overridePath, "/prompts/json/" + systemPromptName + ".md", systemPromptName + " json system template"),
                cpg, rootVariableIds, leafVariableIds, multiUsedVariableIds, duplicateNameLeafIds, repeatedArgumentOperationIds);
    }

    public String jsonUserPromptTemplate(String overridePath, String cpg, String rootVariableIds, String leafVariableIds, String multiUsedVariableIds, String duplicateNameLeafIds, String repeatedArgumentOperationIds) throws IOException {
        return fill(readTemplate(overridePath, "/prompts/json/" + file + "_user.md", file + " json user template"),
                cpg, rootVariableIds, leafVariableIds, multiUsedVariableIds, duplicateNameLeafIds, repeatedArgumentOperationIds);
    }

    public String jsonResultFilename() {
        return file + "_result.json";
    }

    public String callDetailsFilename() {
        return file + "_call_details.json";
    }

    public String markdownResultFilename() {
        return file + "_result.md";
    }

    public String promptFilename() {
        return file + "_prompt.md";
    }

    public String jsonUserPromptFilename() {
        return file + "_user_prompt.md";
    }

    public String jsonSystemPromptFilename() {
        return systemPromptName + "_prompt.md";
    }
}
