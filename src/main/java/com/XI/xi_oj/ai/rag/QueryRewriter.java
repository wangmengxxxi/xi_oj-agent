package com.XI.xi_oj.ai.rag;

import com.XI.xi_oj.ai.event.AiConfigChangedEvent;
import com.XI.xi_oj.service.AiConfigService;
import com.XI.xi_oj.utils.AiEncryptUtil;
import com.XI.xi_oj.utils.TimeUtil;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class QueryRewriter {

    private static final String CACHE_PREFIX = "ai:query:rewrite:";
    private static final long CACHE_TTL_MINUTES = 120;
    private static final int MIN_QUERY_LENGTH = 4;
    private static final int MAX_DIRECT_QUERY_LENGTH = 50;

    private static final String REWRITE_PROMPT = """
            你是一个搜索查询优化器。请将用户的口语化问题改写为更适合 OJ 算法知识库语义检索的描述。
            要求：
            1. 保留用户核心意图，不改变含义；
            2. 补充算法、数据结构、边界条件、复杂度等相关关键词；
            3. 展开缩写和口语表达，例如 dp 改为动态规划；
            4. 只输出一句中文检索 query，不要解释，不要加前缀。

            用户问题：%s
            改写后：""";

    @Resource
    private AiConfigService aiConfigService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${ai.encrypt.key}")
    private String encryptKey;

    private volatile ChatModel rewriteModel;

    @PostConstruct
    private void init() {
        this.rewriteModel = buildRewriteModel();
    }

    @EventListener
    public void onConfigChanged(AiConfigChangedEvent event) {
        String key = event.getConfigKey();
        if (key.startsWith("ai.rewrite.") || key.equals("ai.provider.api_key_encrypted") || key.equals("ai.model.base_url")) {
            this.rewriteModel = buildRewriteModel();
            log.info("[QueryRewriter] rewriteModel rebuilt due to config change: {}", key);
        }
    }

    public String rewrite(String originalQuery) {
        if (originalQuery == null || originalQuery.isBlank()) {
            return originalQuery;
        }
        String trimmed = originalQuery.trim();
        if (trimmed.length() < MIN_QUERY_LENGTH || trimmed.length() > MAX_DIRECT_QUERY_LENGTH) {
            return trimmed;
        }

        String cacheKey = CACHE_PREFIX + DigestUtils.md5DigestAsHex(trimmed.getBytes(StandardCharsets.UTF_8));
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("[QueryRewrite] cache hit, query={}", trimmed);
            return cached;
        }

        ChatModel model = this.rewriteModel;
        if (model == null) {
            return trimmed;
        }

        try {
            String rewritten = model.chat(String.format(REWRITE_PROMPT, trimmed));
            rewritten = sanitize(rewritten);
            if (rewritten.isBlank()) {
                return trimmed;
            }
            stringRedisTemplate.opsForValue().set(cacheKey, rewritten, TimeUtil.minutes(CACHE_TTL_MINUTES));
            log.info("[QueryRewrite] {} -> {}", trimmed, rewritten);
            return rewritten;
        } catch (Exception e) {
            log.warn("[QueryRewrite] failed, fallback to original query: {}", e.getMessage());
            return trimmed;
        }
    }

    private String sanitize(String rewritten) {
        if (rewritten == null) {
            return "";
        }
        String text = rewritten.trim();
        text = text.replaceFirst("^(改写后|检索 query|查询|Query)[:：]\\s*", "");
        int lineBreak = text.indexOf('\n');
        if (lineBreak > 0) {
            text = text.substring(0, lineBreak).trim();
        }
        if (text.length() > 120) {
            text = text.substring(0, 120);
        }
        return text;
    }

    private ChatModel buildRewriteModel() {
        String apiKey = providerApiKey();
        String baseUrl = config("ai.model.base_url", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        String modelName = config("ai.rewrite.model_name", "qwen-turbo");
        if (apiKey.isBlank()) {
            return null;
        }
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(doubleConfig("ai.rewrite.temperature", 0.1D))
                .maxTokens(intConfig("ai.rewrite.max_tokens", 256))
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
            log.warn("[QueryRewrite] decrypt api key failed: {}", e.getMessage());
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

    private double doubleConfig(String key, double fallback) {
        try {
            return Double.parseDouble(config(key, String.valueOf(fallback)));
        } catch (Exception e) {
            return fallback;
        }
    }
}
