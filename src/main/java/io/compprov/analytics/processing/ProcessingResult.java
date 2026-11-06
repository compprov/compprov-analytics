package io.compprov.analytics.processing;

import io.compprov.analytics.ai.Prompt;
import io.compprov.analytics.ai.ReducedPromptProcessingResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * One CPG snapshot's findings: structural highlights, the two deterministic validity flags
 * (replay and chronology), and — one per {@link Prompt}, if prompts were executed — the reduced
 * LLM risk score used to build the summary tables.
 */
public class ProcessingResult {
    private final List<String> highlights = new ArrayList<>();
    private final LinkedHashMap<Prompt, ReducedPromptProcessingResult> llmResults = new LinkedHashMap<>();
    private boolean validCalculation = true;
    private boolean validChronology = true;
    private boolean failed = false;
    private final String processingDir;
    private final String sourceFile;

    public ProcessingResult(String processingDir, String sourceFile) {
        this.processingDir = processingDir;
        this.sourceFile = sourceFile;
    }

    /**
     * Appends a human-readable line describing a structural finding (e.g. an unused root, a broken-chronology hit).
     */
    public void addHighlight(String highlight) {
        highlights.add(highlight);
    }

    public void addLlmResult(Prompt prompt, ReducedPromptProcessingResult llmResult) {
        llmResults.put(prompt, llmResult);
    }

    /**
     * Marks this snapshot's recomputed values as disagreeing with the recorded ones.
     */
    public void invalidateCalculation() {
        validCalculation = false;
    }

    public List<String> getHighlights() {
        return highlights;
    }

    /**
     * @return this snapshot's LLM risk scores, keyed by {@link Prompt}, in the order they were processed
     */
    public LinkedHashMap<Prompt, ReducedPromptProcessingResult> getLlmResults() {
        return llmResults;
    }

    /**
     * @return {@code false} if replaying any operation produced a value differing from the recorded one
     */
    public boolean isValidCalculation() {
        return validCalculation;
    }

    /**
     * @return {@code false} if any operation's or variable's timestamps were found out of causal order
     */
    public boolean isValidChronology() {
        return validChronology;
    }

    /**
     * Marks this snapshot as having at least one timestamp out of causal order.
     */
    public void invalidateChronology() {
        validChronology = false;
    }

    public String getProcessingDir() {
        return processingDir;
    }

    /**
     * Marks this snapshot's prompt processing as failed; its deterministic findings are still reported.
     */
    public void markFailed() {
        failed = true;
    }

    public boolean isFailed() {
        return failed;
    }

    public String getSourceFile() {
        return sourceFile;
    }
}
