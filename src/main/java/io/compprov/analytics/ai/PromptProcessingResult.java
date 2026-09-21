package io.compprov.analytics.ai;

/** Field names are snake_case to match the JSON the prompt asks the model to return; risk_score is 0-100. */
public record PromptProcessingResult(Double risk_score, String markdown_report) {

}
