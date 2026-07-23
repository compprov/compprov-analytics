package io.compprov.analytics.ai;

import java.io.IOException;

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

    public String getDescription() {
        return description;
    }

    public String markdownPromptTemplate(String cpg) throws IOException {
        try {
            return new String(Prompt.class.getResourceAsStream("/prompts/markdown/" + file + "_prompt.md").readAllBytes())
                    .replace("$CPG$", cpg);
        } catch (Throwable e) {
            throw new IOException("Unable to read " + file + " markdown template", e);
        }
    }

    public String jsonPromptTemplate(String cpg) throws IOException {
        try {
            return new String(Prompt.class.getResourceAsStream("/prompts/json/" + file + "_prompt.md").readAllBytes())
                    .replace("$CPG$", cpg);
        } catch (Throwable e) {
            throw new IOException("Unable to read " + file + " json template", e);
        }
    }

    public String jsonResultFilename() {
        return file + "_result.json";
    }

    public String markdownResultFilename() {
        return file + "_result.md";
    }

    public String promptFilename() {
        return file + "_prompt.md";
    }
}
