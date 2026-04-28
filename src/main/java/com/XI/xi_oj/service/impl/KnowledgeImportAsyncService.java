package com.XI.xi_oj.service.impl;

import com.XI.xi_oj.ai.rag.KnowledgeInitializer;
import com.XI.xi_oj.ai.rag.parser.DocumentParser;
import com.XI.xi_oj.ai.rag.parser.DocumentParser.ParseResult;
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
        taskStatusMap.put(taskId, status);

        boolean acquired = false;
        try {
            acquired = importSemaphore.tryAcquire();
            if (!acquired) {
                status.setStatus("queued");
                importSemaphore.acquire();
                acquired = true;
            }
            status.setStatus("processing");

            ParseResult result = parser.parseWithImages(
                    new ByteArrayInputStream(fileBytes), filename);
            int count = knowledgeInitializer.parseAndStore(result.markdownBlocks());
            knowledgeInitializer.validateImportedCount(count);

            status.setStatus("completed");
            status.setMessage("成功导入 " + count + " 条知识条目，提取 "
                    + result.imageUrls().size() + " 张图片");
            log.info("[Knowledge Import Async] taskId={}, file={}, count={}, images={}",
                    taskId, filename, count, result.imageUrls().size());
        } catch (Exception e) {
            status.setStatus("failed");
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
    }
}
