package io.compprov.analytics;

import io.compprov.analytics.ai.Prompt;
import io.compprov.analytics.ai.PromptProcessingResult;
import io.compprov.analytics.ai.ReducedPromptProcessingResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class ProcessingResult {
    private final List<String> highlights = new ArrayList<>();
    private LinkedHashMap<Prompt, ReducedPromptProcessingResult> llmResults = new LinkedHashMap<>();
    private boolean validCalculation = true;

    public void addHighlight(String highlight) {
        highlights.add(highlight);
    }

    public void addLlmResult(Prompt prompt, PromptProcessingResult llmResult) {
        llmResults.put(prompt, new ReducedPromptProcessingResult(llmResult.verdict(), llmResult.confidence_score()));
    }

    public void invalidateCalculation() {
        validCalculation = false;
    }

    public List<String> getHighlights() {
        return highlights;
    }

    public LinkedHashMap<Prompt, ReducedPromptProcessingResult> getLlmResults() {
        return llmResults;
    }

    public boolean isValidCalculation() {
        return validCalculation;
    }
}
