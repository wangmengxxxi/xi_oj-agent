package com.XI.xi_oj.controller;

import com.XI.xi_oj.ai.rag.KnowledgeInitializer;
import com.XI.xi_oj.ai.rag.parser.DocumentParser;
import com.XI.xi_oj.annotation.AuthCheck;
import com.XI.xi_oj.common.BaseResponse;
import com.XI.xi_oj.common.ErrorCode;
import com.XI.xi_oj.common.ResultUtils;
import com.XI.xi_oj.constant.UserConstant;
import com.XI.xi_oj.exception.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/admin/knowledge")
@Slf4j
public class KnowledgeImportController {

    @Resource
    private KnowledgeInitializer knowledgeInitializer;

    @Resource
    private List<DocumentParser> documentParsers;

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

        String markdownBlocks = parser.parse(file.getInputStream(), filename);
        int count = knowledgeInitializer.parseAndStore(markdownBlocks);
        knowledgeInitializer.validateImportedCount(count);

        log.info("[Knowledge Import] file={}, format={}, count={}", filename, extension, count);
        return ResultUtils.success("成功导入 " + count + " 条知识条目（来源: " + extension.toUpperCase() + "）");
    }
}
