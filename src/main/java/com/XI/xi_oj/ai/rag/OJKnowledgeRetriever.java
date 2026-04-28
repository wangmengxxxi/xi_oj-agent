package com.XI.xi_oj.ai.rag;

import com.XI.xi_oj.ai.agent.AiModelHolder;
import com.XI.xi_oj.utils.TimeUtil;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
public class OJKnowledgeRetriever {

    @Resource
    private MilvusEmbeddingStore embeddingStore;

    @Resource(name = "questionEmbeddingStore")
    private MilvusEmbeddingStore questionEmbeddingStore;

    @Resource
    private AiModelHolder aiModelHolder;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String RAG_CACHE_PREFIX = "ai:rag:cache:";
    private static final long RAG_CACHE_TTL_MINUTES = 60;

    public String retrieve(String query, int topK, double minScore) {
        String cacheKey = RAG_CACHE_PREFIX + DigestUtils.md5DigestAsHex(
                (query + "|" + topK + "|" + minScore).getBytes(StandardCharsets.UTF_8));
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("[RAG Cache] HIT key={}", cacheKey);
            return cached;
        }
        String result = doRetrieve(query, topK, minScore);
        stringRedisTemplate.opsForValue().set(cacheKey, result, TimeUtil.minutes(RAG_CACHE_TTL_MINUTES));
        return result;
    }

    private String doRetrieve(String query, int topK, double minScore) {
        try {
            Embedding queryEmbedding = aiModelHolder.getEmbeddingModel().embed(query).content();
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(topK)
                    .minScore(minScore)
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
            List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

            String context = matches.stream()
                    .filter(match -> match.score() >= minScore)
                    .filter(match -> match.embedded() != null)
                    .map(EmbeddingMatch::embedded)
                    .map(this::formatSegmentWithImages)
                    .collect(Collectors.joining("\n\n"));

            return context.isBlank() ? "无相关知识点" : context;
        } catch (Exception e) {
            log.warn("[RAG] 知识库检索失败，向量数据库可能不可用: {}", e.getMessage());
            return "无相关知识点";
        }
    }

    private String formatSegmentWithImages(TextSegment segment) {
        String text = segment.text();
        String imageUrls = segment.metadata().getString("image_urls");
        if (imageUrls == null || imageUrls.isBlank()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        for (String url : imageUrls.split(",")) {
            String trimmed = url.trim();
            if (!trimmed.isEmpty()) {
                sb.append("\n![配图](").append(trimmed).append(")");
            }
        }
        return sb.toString();
    }

    public List<Long> retrieveSimilarQuestions(Long questionId, String questionContent) {
        return retrieveSimilarQuestions(questionId, questionContent, null);
    }

    public List<Long> retrieveSimilarQuestions(Long questionId, String questionContent, String difficulty) {
        String normalizedDifficulty = normalizeDifficulty(difficulty);
        String cacheKey = RAG_CACHE_PREFIX + "similar:" + DigestUtils.md5DigestAsHex(
                (questionId + "|" + questionContent + "|" + normalizedDifficulty).getBytes(StandardCharsets.UTF_8));

        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("[RAG Cache] HIT key={}", cacheKey);
            if (cached.isBlank()) {
                return Collections.emptyList();
            }
            return Arrays.stream(cached.split(","))
                    .map(Long::parseLong)
                    .collect(Collectors.toList());
        }

        List<Long> result = doretrieveSimilarQuestions(questionId, questionContent, normalizedDifficulty);
        String toCache = result.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        stringRedisTemplate.opsForValue().set(cacheKey, toCache, TimeUtil.minutes(RAG_CACHE_TTL_MINUTES));
        return result;
    }

    private List<Long> doretrieveSimilarQuestions(Long questionId, String questionContent, String difficulty) {
        try {
            Embedding queryEmbedding = aiModelHolder.getEmbeddingModel().embed(questionContent).content();
            EmbeddingSearchRequest embeddingSearchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(8)
                    .minScore(0.75)
                    .build();
            return questionEmbeddingStore.search(embeddingSearchRequest)
                    .matches()
                    .stream()
                    .filter(match -> match.score() >= 0.75)
                    .filter(match -> match.embedded() != null)
                    .filter(match -> "题目".equals(match.embedded().metadata().getString("content_type")))
                    .filter(match -> difficulty == null || difficulty.equals(
                            normalizeDifficulty(match.embedded().metadata().getString("difficulty"))))
                    .map(EmbeddingMatch::embedded)
                    .map(segment -> segment.metadata().getLong("question_id"))
                    .filter(id -> id != null)
                    .filter(id -> !id.equals(questionId))
                    .limit(4)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[RAG] 相似题目检索失败，向量数据库可能不可用: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public String retrieveByType(String query, String contentTypes, int topK, double minScore) {
        String cacheKey = RAG_CACHE_PREFIX + "type:" + DigestUtils.md5DigestAsHex(
                (query + "|" + contentTypes + "|" + topK + "|" + minScore).getBytes(StandardCharsets.UTF_8));

        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("[RAG Cache] HIT key={}", cacheKey);
            return cached;
        }

        List<String> typeList = Arrays.stream(contentTypes.split(","))
                .map(String::trim)
                .collect(Collectors.toList());
        List<EmbeddingMatch<TextSegment>> matches = doretrieveByType(query, topK, minScore);
        String result = matches.stream()
                .filter(match -> match.score() >= minScore)
                .filter(match -> match.embedded() != null)
                .filter(match -> typeList.contains(match.embedded().metadata().getString("content_type")))
                .limit(topK)
                .map(EmbeddingMatch::embedded)
                .map(TextSegment::text)
                .collect(Collectors.joining("\n\n"));
        result = result.isBlank() ? "无相关知识点" : result;

        stringRedisTemplate.opsForValue().set(cacheKey, result, TimeUtil.minutes(RAG_CACHE_TTL_MINUTES));
        return result;
    }

    private List<EmbeddingMatch<TextSegment>> doretrieveByType(String query, int topK, double minScore) {
        try {
            Embedding queryEmbedding = aiModelHolder.getEmbeddingModel().embed(query).content();
            return embeddingStore.search(
                    EmbeddingSearchRequest.builder()
                            .queryEmbedding(queryEmbedding)
                            .maxResults(topK * 2)
                            .minScore(minScore)
                            .build()
            ).matches();
        } catch (Exception e) {
            log.warn("[RAG] 分类知识检索失败，向量数据库可能不可用: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public void clearRagCache() {
        Set<String> keys = stringRedisTemplate.keys(RAG_CACHE_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
            log.info("[RAG Cache] 已清理 {} 条 RAG 缓存", keys.size());
        }
    }

    private String normalizeDifficulty(String difficulty) {
        if (difficulty == null || difficulty.isBlank() || "未知".equals(difficulty)) {
            return null;
        }
        return difficulty.trim().toLowerCase(Locale.ROOT);
    }
}
