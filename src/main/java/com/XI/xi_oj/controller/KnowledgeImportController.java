package com.XI.xi_oj.controller;

import com.XI.xi_oj.ai.rag.KnowledgeInitializer;
import com.XI.xi_oj.ai.rag.parser.DocumentParser;
import com.XI.xi_oj.ai.rag.parser.DocumentParser.ParseResult;
import com.XI.xi_oj.annotation.AuthCheck;
import com.XI.xi_oj.common.BaseResponse;
import com.XI.xi_oj.common.ErrorCode;
import com.XI.xi_oj.common.ResultUtils;
import com.XI.xi_oj.constant.UserConstant;
import com.XI.xi_oj.exception.BusinessException;
import com.XI.xi_oj.service.impl.KnowledgeImportAsyncService;
import com.XI.xi_oj.service.impl.KnowledgeImportAsyncService.ImportTaskStatus;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/knowledge")
@Slf4j
public class KnowledgeImportController {

    private static final long ASYNC_THRESHOLD = 10 * 1024 * 1024; // 10MB

    @Resource
    private KnowledgeInitializer knowledgeInitializer;

    @Resource
    private List<DocumentParser> documentParsers;

    @Resource
    private KnowledgeImportAsyncService importAsyncService;

    @PostMapping("/import")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<String> importKnowledge(@RequestPart("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件不能为空");
        }

        String filename = file.getOriginalFilename();
        String extension = StringUtils.getFilenameExtension(filename);

        if ("md".equalsIgnoreCase(extension) || "markdown".equalsIgnoreCase(extension)) {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            int count = knowledgeInitializer.parseAndStore(content);
            knowledgeInitializer.validateImportedCount(count);
            log.info("[Knowledge Import] file={}, format=md, count={}", filename, count);
            return ResultUtils.success("成功导入 " + count + " 条知识条目");
        }

        DocumentParser parser = documentParsers.stream()
                .filter(p -> p.supports(extension))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAMS_ERROR,
                        "不支持的文件格式: " + extension + "，仅支持 .md / .pdf / .docx"));

        // 大文件走异步处理
        if (file.getSize() > ASYNC_THRESHOLD) {
            String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            byte[] fileBytes = file.getBytes();
            importAsyncService.importAsync(taskId, fileBytes, filename, parser);
            log.info("[Knowledge Import] large file, async taskId={}, file={}, size={}MB",
                    taskId, filename, file.getSize() / 1024 / 1024);
            return ResultUtils.success("文件较大，已提交异步导入，任务ID: " + taskId);
        }

        ParseResult result = parser.parseWithImages(file.getInputStream(), filename);
        int count = knowledgeInitializer.parseAndStore(result.markdownBlocks());
        knowledgeInitializer.validateImportedCount(count);

        String imageInfo = result.imageUrls().isEmpty() ? "" :
                "，提取 " + result.imageUrls().size() + " 张图片";
        log.info("[Knowledge Import] file={}, format={}, count={}, images={}",
                filename, extension, count, result.imageUrls().size());
        return ResultUtils.success("成功导入 " + count + " 条知识条目" + imageInfo);
    }

    @GetMapping("/import/status/{taskId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<ImportTaskStatus> getImportStatus(@PathVariable String taskId) {
        ImportTaskStatus status = importAsyncService.getTaskStatus(taskId);
        if (status == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "任务不存在: " + taskId);
        }
        return ResultUtils.success(status);
    }
}
