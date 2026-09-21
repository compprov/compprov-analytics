package io.compprov.analytics.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public enum Prompt {

    CALCULATION_OMISSION("Calculation omission", "calculation_omission"),
    LINEAGE_DISCONNECTION("Lineage disconnection", "lineage_disconnection_and_context_substitution"),
    PRECISION_TAMPERING("Precision tampering", "precision_tampering"),
    SEMANTIC_VIOLATION("Semantic violation", "semantic_type_and_context_cast_attack"),
    DOUBLE_COUNTING("Double counting", "topological_accumulation_fraud_via_double_counting"),
    TOPOLOGICAL_FRAUD("Topological fraud", "topological_fraud");

    private final String description;
    private final String file;

    Prompt(String description, String file) {
        this.description = description;
        this.file = file;
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
                + ". Valid names are: " + String.join(", ", java.util.Arrays.stream(values()).map(Prompt::getTemplateName).toList()));
    }

    public String markdownTemplateKey() {
        return file + "_prompt";
    }

    public String jsonUserTemplateKey() {
        return file + "_user";
    }

    public static String sharedJsonSystemTemplateKey() {
        return "shared_system";
    }

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

    public String markdownPromptTemplate(String overridePath, String cpg, String rootVariableIds, String leafVariableIds, String multiUsedVariableIds, String duplicateNameLeafIds) throws IOException {
        return readTemplate(overridePath, "/prompts/markdown/" + file + "_prompt.md", file + " markdown template")
                .replace("$CPG$", cpg)
                .replace("$ROOT_VARIABLE_IDS$", rootVariableIds)
                .replace("$LEAF_VARIABLE_IDS$", leafVariableIds)
                .replace("$MULTIUSED_VARIABLE_IDS$", multiUsedVariableIds)
                .replace("$DUPLICATE_NAME_LEAF_IDS$", duplicateNameLeafIds);
    }

    public static String sharedJsonSystemPromptTemplate(String overridePath, String cpg, String rootVariableIds, String leafVariableIds, String multiUsedVariableIds, String duplicateNameLeafIds) throws IOException {
        return readTemplate(overridePath, "/prompts/json/shared_system.md", "shared json system template")
                .replace("$CPG$", cpg)
                .replace("$ROOT_VARIABLE_IDS$", rootVariableIds)
                .replace("$LEAF_VARIABLE_IDS$", leafVariableIds)
                .replace("$MULTIUSED_VARIABLE_IDS$", multiUsedVariableIds)
                .replace("$DUPLICATE_NAME_LEAF_IDS$", duplicateNameLeafIds);
    }

    public String jsonUserPromptTemplate(String overridePath) throws IOException {
        return readTemplate(overridePath, "/prompts/json/" + file + "_user.md", file + " json user template");
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

    public static String sharedJsonSystemPromptFilename() {
        return "system_prompt.md";
    }
}
