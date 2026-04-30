package com.XI.xi_oj.ai.rag;

import com.XI.xi_oj.ai.event.AiConfigChangedEvent;
import com.XI.xi_oj.service.AiConfigService;
import com.XI.xi_oj.utils.AiEncryptUtil;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Base64;

@Component
@Slf4j
public class VisionModelHolder {

    private static final String VL_PROMPT =
            "请用一句话描述这张图片的内容，重点说明它展示了什么概念、数据结构或算法过程。只输出描述，不要加前缀。";

    @Resource
    private AiConfigService aiConfigService;

    @Value("${ai.encrypt.key}")
    private String encryptKey;

    private volatile ChatModel visionModel;

    @PostConstruct
    private void init() {
        this.visionModel = buildVisionModel();
    }

    @EventListener
    public void onConfigChanged(AiConfigChangedEvent event) {
        String key = event.getConfigKey();
        if (key.startsWith("ai.vl.") || key.equals("ai.provider.api_key_encrypted") || key.equals("ai.model.base_url")) {
            this.visionModel = buildVisionModel();
            log.info("[VisionModelHolder] visionModel rebuilt due to config change: {}", key);
        }
    }

    private static final int MAX_RETRIES = 2;

    public String generateCaption(byte[] imageData, String mimeType) {
        ChatModel model = this.visionModel;
        if (model == null || imageData == null || imageData.length == 0) {
            return "";
        }
        String base64 = Base64.getEncoder().encodeToString(imageData);
        String dataUri = "data:" + mimeType + ";base64," + base64;
        UserMessage msg = UserMessage.from(
                ImageContent.from(dataUri),
                TextContent.from(VL_PROMPT)
        );
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                if (attempt > 0) {
                    Thread.sleep(1000L * attempt);
                }
                String caption = model.chat(msg).aiMessage().text();
                return caption != null ? caption.trim() : "";
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "";
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    log.warn("[VisionModelHolder] caption failed after {} retries: {}", MAX_RETRIES, e.getMessage());
                }
            }
        }
        return "";
    }

    public int getConcurrency() {
        int val = intConfig("ai.vl.concurrency", 4);
        return Math.max(1, Math.min(val, 16));
    }

    public boolean isAvailable() {
        return visionModel != null;
    }

    private ChatModel buildVisionModel() {
        String apiKey = providerApiKey();
        String baseUrl = config("ai.model.base_url", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        String modelName = config("ai.vl.model_name", "qwen-vl-plus");
        if (apiKey.isBlank()) {
            log.warn("[VisionModelHolder] API key is empty, VL model not available");
            return null;
        }
        log.info("[VisionModelHolder] building VL model: {}", modelName);
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.3)
                .maxTokens(256)
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    private String providerApiKey() {
        String encrypted = aiConfigService.getConfigValue("ai.provider.api_key_encrypted");
        if (encrypted == null || encrypted.isBlank()) {
            return "";
        }
        try {
            return AiEncryptUtil.decrypt(encryptKey, encrypted);
        } catch (Exception e) {
            log.warn("[VisionModelHolder] decrypt api key failed: {}", e.getMessage());
            return "";
        }
    }

    private String config(String key, String fallback) {
        String value = aiConfigService.getConfigValue(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int intConfig(String key, int fallback) {
        try {
            return Integer.parseInt(config(key, String.valueOf(fallback)));
        } catch (Exception e) {
            return fallback;
        }
    }
}
