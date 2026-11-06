package io.compprov.analytics.processing;

public enum PromptProcessingMode {

    GENERATE_MARKDOWN(false),
    EXECUTE_ASYNC(true),
    PROCESS_SYNC(true),
    PROCESS_ASYNC(true),
    CONTINUE_ASYNC(true);
    private final boolean jsonPrompts;

    PromptProcessingMode(boolean jsonPrompts) {
        this.jsonPrompts = jsonPrompts;
    }

    public boolean isJsonPrompts() {
        return jsonPrompts;
    }
}
