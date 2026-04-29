package com.XI.xi_oj.controller;

import com.XI.xi_oj.ai.rag.QuestionVectorSyncService;
import com.XI.xi_oj.annotation.AuthCheck;
import com.XI.xi_oj.common.BaseResponse;
import com.XI.xi_oj.common.ErrorCode;
import com.XI.xi_oj.common.ResultUtils;
import com.XI.xi_oj.exception.BusinessException;
import com.XI.xi_oj.model.dto.ai.AiConfigUpdateRequest;
import com.XI.xi_oj.service.AiConfigService;
import com.XI.xi_oj.utils.AiEncryptUtil;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/admin/ai")
@Slf4j
public class AiConfigController {
    @Autowired
    private AiConfigService aiConfigService;

    @Autowired
    private QuestionVectorSyncService questionVectorSyncService;

    @Value("${ai.encrypt.key}")
    private String encryptKey;

    private static final List<String> READABLE_KEYS = Arrays.asList(
            "ai.global.enable",
            "ai.provider", "ai.provider.api_key_encrypted", "ai.embedding.api_key_encrypted",
            "ai.model.name", "ai.model.base_url", "ai.model.embedding_name",
            "ai.rag.top_k", "ai.rag.similarity_threshold",
            "ai.rerank.enabled", "ai.rerank.model_name", "ai.rerank.endpoint", "ai.rerank.top_n",
            "ai.agent.mode", "ai.agent.max_steps", "ai.agent.tool_max_retry",
            "ai.prompt.code_analysis", "ai.prompt.wrong_analysis", "ai.prompt.question_parse",
            "ai.prompt.chat_system", "ai.prompt.agent_system"
    );

    private static final Set<String> ENCRYPTED_KEYS = Set.of(
            "ai.provider.api_key_encrypted", "ai.embedding.api_key_encrypted"
    );
    @GetMapping("/config")
    @AuthCheck(mustRole = "admin")
    public BaseResponse<Map<String, String>> getConfig() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : READABLE_KEYS) {
            String value = aiConfigService.getConfigValue(key);
            if (ENCRYPTED_KEYS.contains(key) && value != null && !value.isEmpty()) {
                try {
                    String plain = AiEncryptUtil.decrypt(encryptKey, value);
                    result.put(key, maskApiKey(plain));
                } catch (Exception e) {
                    result.put(key, "****");
                }
            } else {
                result.put(key, value);
            }
        }
        return ResultUtils.success(result);
    }

    @PostMapping("/config")
    @AuthCheck(mustRole = "admin")
    public BaseResponse<String> updateConfig(@RequestBody AiConfigUpdateRequest request) {
        String key = request.getConfigKey();
        String value = request.getConfigValue();

        if (ENCRYPTED_KEYS.contains(key)) {
            if (value == null || value.isEmpty() || value.startsWith("****")) {
                return ResultUtils.success("密钥未修改，跳过更新");
            }
            String encrypted = AiEncryptUtil.encrypt(encryptKey, value);
            aiConfigService.updateConfig(key, encrypted);
            return ResultUtils.success("密钥已加密保存，模型即时重建生效");
        }

        aiConfigService.updateConfig(key, value);
        return ResultUtils.success("配置更新成功，模型与 RAG 参数即时重建生效，Prompt 类配置最多 5 分钟内生效");
    }

    @PostMapping("/provider/test")
    @AuthCheck(mustRole = "admin")
    public BaseResponse<String> testProviderConnection(@RequestBody Map<String, String> request) {
        String apiKey = request.get("apiKey");
        String baseUrl = request.get("baseUrl");
        String modelName = request.get("modelName");
        if (apiKey == null || baseUrl == null || modelName == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "apiKey、baseUrl、modelName 不能为空");
        }
        try {
            OpenAiChatModel testModel = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .maxTokens(10)
                    .build();
            String reply = testModel.chat("hi");
            return ResultUtils.success("连接成功，模型响应：" + (reply.length() > 50 ? reply.substring(0, 50) + "..." : reply));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "连接失败：" + e.getMessage());
        }
    }

    private static String maskApiKey(String plain) {
        if (plain == null || plain.length() <= 4) {
            return "****";
        }
        return "****" + plain.substring(plain.length() - 4);
    }

    @PostMapping("/question-vector/rebuild")
    @AuthCheck(mustRole = "admin")
    public BaseResponse<String> rebuildQuestionVectors() {
        new Thread(() -> {
            try {
                int count = questionVectorSyncService.rebuildQuestionVectors();
                log.info("[QuestionVector] async rebuild finished, count={}", count);
            } catch (Exception e) {
                log.error("[QuestionVector] async rebuild failed", e);
            }
        }, "question-vector-rebuild").start();
        return ResultUtils.success("题目向量重建任务已提交，请稍后查看日志确认结果");
    }
}