package io.compprov.analytics.ai;

public record PromptProcessingResult(String verdict, Double confidence_score, String markdown_report) {

}
