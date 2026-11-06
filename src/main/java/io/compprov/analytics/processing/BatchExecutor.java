package io.compprov.analytics.processing;

import dev.langchain4j.model.batch.BatchRequest;
import dev.langchain4j.model.batch.BatchState;
import dev.langchain4j.model.chat.BatchChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import io.compprov.analytics.ai.Prompt;
import io.compprov.analytics.ai.PromptProcessingResult;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.compprov.analytics.Commons.LOGGER;

public class BatchExecutor extends Executor {

    private final BatchChatModel batchChatModel;

    public BatchExecutor(List<Prompt> activePrompts, BatchChatModel batchChatModel, Double cleanThreshold, Double suspiciousThreshold) {
        super(activePrompts, cleanThreshold, suspiciousThreshold);
        this.batchChatModel = batchChatModel;
    }

    public BatchData submit(List<ProcessingResult> preProcessed) throws Exception {

        final var sourceFiles = new ArrayList<String>();
        final var processingDirs = new ArrayList<String>();
        final var requests = new ArrayList<ChatRequest>();
        for (var ppr : preProcessed) {
            final var cpgDir = new File(ppr.getProcessingDir());
            final var prompts = collectPrompts(cpgDir);
            sourceFiles.add(ppr.getSourceFile());
            processingDirs.add(cpgDir.getAbsolutePath());
            requests.addAll(prompts.values());
        }

        final var result = batchChatModel.submit(new BatchRequest<>(requests));
        LOGGER.info("Batch submitted: " + result);
        return new BatchData(result.batchId(), activePrompts, sourceFiles, processingDirs);
    }

    public List<ProcessingResult> process(BatchData batchData) throws Exception {

        //wait for result
        var batchResponse = batchChatModel.retrieve(batchData.batchId());
        while (!batchResponse.state().isTerminal()) {
            LOGGER.info("Batch state is: " + batchResponse.state());
            Thread.sleep(10000);
            batchResponse = batchChatModel.retrieve(batchData.batchId());
        }

        //check result, a cancelled/expired/failed batch is processed partially if it has results
        if (batchResponse.results() == null || batchResponse.results().isEmpty()) {
            throw new Exception("Batch execution state is: " + batchResponse.state() + ", no results available");
        }
        if (batchResponse.state() != BatchState.SUCCEEDED) {
            LOGGER.warning("Batch execution state is: " + batchResponse.state() + ", processing available results");
        }

        final var sourceFiles = batchData.sourceFiles();
        final var processingDirs = batchData.processingDirs();
        final var expectedSize = processingDirs.size() * activePrompts.size();
        if (batchResponse.results().size() != expectedSize) {
            // results are matched to prompts by idx, so the model must return one entry per request, failed ones included
            throw new Exception("Unexpected batch results size: " + batchResponse.results().size()
                    + ", expected " + expectedSize + " (" + processingDirs.size() + " files x " + activePrompts.size() + " prompts)."
                    + " The BatchChatModel must return one result per request in submission order, failed requests included");
        }

        //save prompt execution results
        int resultIdx = 0;
        int failedCount = 0;
        final var result = new ArrayList<ProcessingResult>();
        for (int i = 0; i < sourceFiles.size(); i++) {
            final var cpgDir = new File(processingDirs.get(i));
            final var processingResult = buildProcessingResult(cpgDir, sourceFiles.get(i));
            result.add(processingResult);
            for (var prompt : activePrompts) {
                var response = batchResponse.results().get(resultIdx++);
                Optional<PromptProcessingResult> promptProcessingResult;
                if (response.isSuccess()) {
                    promptProcessingResult = processPromptResult(cpgDir, null, prompt, response.response());
                } else {
                    LOGGER.severe("Error in prompt processing " + cpgDir.getName() + " " + prompt.getTemplateName() + " " + response.error());
                    promptProcessingResult = Optional.empty();
                    failedCount++;
                }

                final var reduced = toReducedPromptProcessingResult(promptProcessingResult);
                LOGGER.info(cpgDir.getName() + " " + prompt.getTemplateName() + ". Verdict: " + reduced.display());
                processingResult.addLlmResult(prompt, reduced);
            }
        }
        if (failedCount > 0) {
            LOGGER.warning("Batch processed partially: " + failedCount + " of " + expectedSize + " requests failed");
        }
        return result;
    }
}
