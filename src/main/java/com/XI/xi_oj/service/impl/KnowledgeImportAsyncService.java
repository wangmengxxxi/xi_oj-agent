package com.XI.xi_oj.service.impl;

import com.XI.xi_oj.ai.rag.KnowledgeInitializer;
import com.XI.xi_oj.ai.rag.parser.DocumentParser;
import com.XI.xi_oj.ai.rag.parser.DocumentParser.ParseResult;
import com.XI.xi_oj.ai.rag.parser.ImportProgressCallback;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Service
@Slf4j
public class KnowledgeImportAsyncService {

    private final KnowledgeInitializer knowledgeInitializer;
    private final ConcurrentHashMap<String, ImportTaskStatus> taskStatusMap = new ConcurrentHashMap<>();
    private final Semaphore importSemaphore = new Semaphore(2);

    public KnowledgeImportAsyncService(KnowledgeInitializer knowledgeInitializer) {
        this.knowledgeInitializer = knowledgeInitializer;
    }

    @Async
    public void importAsync(String taskId, byte[] fileBytes, String filename,
                            DocumentParser parser) {
        ImportTaskStatus status = new ImportTaskStatus();
        status.setStatus("processing");
        status.setFilename(filename);
        status.setProgress(0);
        status.setCurrentStep("等待中");
        taskStatusMap.put(taskId, status);

        ImportProgressCallback callback = new ImportProgressCallback() {
            @Override
            public void onStep(String stepName, int progressPercent) {
                status.setCurrentStep(stepName);
                status.setProgress(progressPercent);
            }

            @Override
            public void onImageCaptionProgress(int completed, int total) {
                int pct = 30 + (int) ((completed / (double) total) * 40);
                status.setProgress(Math.min(pct, 70));
                status.setCurrentStep("生成图片描述 (" + completed + "/" + total + ")");
            }
        };

        boolean acquired = false;
        try {
            acquired = importSemaphore.tryAcquire();
            if (!acquired) {
                status.setStatus("queued");
                status.setCurrentStep("排队中");
                importSemaphore.acquire();
                acquired = true;
            }
            status.setStatus("processing");

            ParseResult result = parser.parseWithImages(
                    new ByteArrayInputStream(fileBytes), filename, callback);
            status.setProgress(75);
            status.setCurrentStep("存入知识库");

            int count = knowledgeInitializer.parseAndStore(result.markdownBlocks());
            knowledgeInitializer.validateImportedCount(count);

            status.setStatus("completed");
            status.setProgress(100);
            status.setCurrentStep("完成");
            status.setMessage("成功导入 " + count + " 条知识条目，提取 "
                    + result.imageUrls().size() + " 张图片");
            log.info("[Knowledge Import Async] taskId={}, file={}, count={}, images={}",
                    taskId, filename, count, result.imageUrls().size());
        } catch (Exception e) {
            status.setStatus("failed");
            status.setProgress(0);
            status.setCurrentStep("失败");
            status.setMessage("导入失败: " + e.getMessage());
            log.error("[Knowledge Import Async] taskId={}, file={}", taskId, filename, e);
        } finally {
            if (acquired) {
                importSemaphore.release();
            }
        }
    }

    public ImportTaskStatus getTaskStatus(String taskId) {
        return taskStatusMap.get(taskId);
    }

    @Data
    public static class ImportTaskStatus {
        private String status;
        private String filename;
        private String message;
        private int progress;
        private String currentStep;
    }
}
