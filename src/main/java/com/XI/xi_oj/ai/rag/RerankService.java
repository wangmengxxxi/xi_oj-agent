package com.XI.xi_oj.ai.rag;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.XI.xi_oj.service.AiConfigService;
import com.XI.xi_oj.utils.AiEncryptUtil;
import dev.langchain4j.rag.content.Content;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@Slf4j
public class RerankService {

    private static final String DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
    private static final int MAX_DOCUMENT_CHARS = 1800;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Resource
    private AiConfigService aiConfigService;

    @Value("${ai.encrypt.key}")
    private String encryptKey;

    public boolean enabled() {
        return Boolean.parseBoolean(config("ai.rerank.enabled", "false"));
    }

    public int topN(int fallback) {
        return intConfig("ai.rerank.top_n", fallback);
    }

    public List<Content> rerank(String query, List<Content> contents, int topN) {
        if (!enabled() || query == null || query.isBlank() || contents == null || contents.size() <= 1) {
            return limit(contents, topN);
        }

        String apiKey = providerApiKey();
        if (apiKey.isBlank()) {
            log.warn("[Rerank] provider api key missing, fallback to vector order");
            return limit(contents, topN);
        }

        try {
            log.info("[Rerank] start, candidates={}, topN={}, query={}", contents.size(), topN, abbreviate(query, 120));
            List<String> documents = contents.stream()
                    .map(this::contentText)
                    .map(this::truncate)
                    .toList();

            JSONObject body = JSONUtil.createObj()
                    .set("model", config("ai.rerank.model_name", "gte-rerank"))
                    .set("input", JSONUtil.createObj()
                            .set("query", query)
                            .set("documents", documents))
                    .set("parameters", JSONUtil.createObj()
                            .set("top_n", Math.min(topN, contents.size()))
                            .set("return_documents", false));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config("ai.rerank.endpoint", DEFAULT_ENDPOINT)))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[Rerank] request failed, status={}, body={}", response.statusCode(), response.body());
                return limit(contents, topN);
            }

            List<Content> reranked = parseResults(response.body(), contents, topN);
            log.info("[Rerank] success, returned={}", reranked.size());
            return reranked;
        } catch (Exception e) {
            log.warn("[Rerank] failed, fallback to vector order: {}", e.getMessage());
            return limit(contents, topN);
        }
    }

    private List<Content> parseResults(String body, List<Content> contents, int topN) {
        JSONObject json = JSONUtil.parseObj(body);
        JSONObject output = json.getJSONObject("output");
        if (output == null) {
            return limit(contents, topN);
        }
        JSONArray results = output.getJSONArray("results");
        if (results == null || results.isEmpty()) {
            return limit(contents, topN);
        }

        List<ScoredIndex> scored = new ArrayList<>();
        for (Object item : results) {
            JSONObject obj = JSONUtil.parseObj(item);
            Integer index = obj.getInt("index");
            Double score = obj.getDouble("relevance_score");
            if (index != null && index >= 0 && index < contents.size()) {
                scored.add(new ScoredIndex(index, score == null ? 0D : score));
            }
        }

        return scored.stream()
                .sorted(Comparator.comparing(ScoredIndex::score).reversed())
                .limit(Math.min(topN, scored.size()))
                .map(scoredIndex -> contents.get(scoredIndex.index()))
                .toList();
    }

    private List<Content> limit(List<Content> contents, int topN) {
        if (contents == null) {
            return List.of();
        }
        return contents.stream()
                .limit(Math.max(1, Math.min(topN, contents.size())))
                .toList();
    }

    private String contentText(Content content) {
        return content != null && content.textSegment() != null ? content.textSegment().text() : "";
    }

    private String truncate(String text) {
        if (text == null || text.length() <= MAX_DOCUMENT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_DOCUMENT_CHARS);
    }

    private String providerApiKey() {
        String encrypted = aiConfigService.getConfigValue("ai.provider.api_key_encrypted");
        if (encrypted == null || encrypted.isBlank()) {
            return "";
        }
        try {
            return AiEncryptUtil.decrypt(encryptKey, encrypted);
        } catch (Exception e) {
            log.warn("[Rerank] decrypt api key failed: {}", e.getMessage());
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

    private String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private record ScoredIndex(int index, double score) {
    }
}
