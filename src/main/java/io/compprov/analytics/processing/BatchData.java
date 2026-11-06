package io.compprov.analytics.processing;

import io.compprov.analytics.ai.Prompt;

import java.util.List;

public record BatchData(
        String batchId,
        List<Prompt> activePrompts,
        List<String> sourceFiles,
        List<String> processingDirs) {
}