package com.XI.xi_oj.ai.rag;

import com.XI.xi_oj.ai.agent.AiModelHolder;
import com.XI.xi_oj.common.ErrorCode;
import com.XI.xi_oj.exception.BusinessException;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public class KnowledgeInitializer implements CommandLineRunner {

    private static final String[] KNOWLEDGE_FILES = {
            "knowledge/algorithm_knowledge.md",
            "knowledge/error_analysis.md",
            "knowledge/common_mistakes.md",
            "knowledge/problem_patterns.md",
            "knowledge/java_common_pitfalls.md",
            "knowledge/algorithm_advanced.md",
            "knowledge/data_structures.md",
            "knowledge/problem_patterns_extended.md",
            "knowledge/error_analysis_extended.md",
            "knowledge/cpp_common_pitfalls.md",
            "knowledge/python_common_pitfalls.md",
            "knowledge/oj_practical_tips.md"
    };

    @Resource
    @Qualifier("embeddingStore")
    private MilvusEmbeddingStore embeddingStore;

    @Resource
    private AiModelHolder aiModelHolder;

    @Resource
    private OJKnowledgeRetriever ojKnowledgeRetriever;

    @Override
    public void run(String... args) {
        if (aiModelHolder.getEmbeddingModel() == null) {
            log.warn("[Knowledge Init] embedding model not available (API Key not configured), skip bootstrap import");
            return;
        }
        if (hasKnowledgeData()) {
            log.info("[Knowledge Init] knowledge collection already has data, skip bootstrap import");
            return;
        }
        log.info("[Knowledge Init] knowledge collection is empty, start importing classpath markdown files");
        StringBuilder allContent = new StringBuilder();
        for (String filePath : KNOWLEDGE_FILES) {
            String content = loadClasspathContent(filePath);
            if (content != null) {
                if (!allContent.isEmpty()) {
                    allContent.append("\n---\n");
                }
                allContent.append(content);
            }
        }
        if (!allContent.isEmpty()) {
            int count = parseAndStore(allContent.toString());
            log.info("[Knowledge Init] total imported {} knowledge blocks from {} files", count, KNOWLEDGE_FILES.length);
        }
    }

    public int parseAndStore(String markdownContent) {
        if (markdownContent == null || markdownContent.isBlank()) {
            return 0;
        }
        String normalized = markdownContent.replace("\uFEFF", "");
        String[] blocks = normalized.split("(?m)^---\\s*$");
        List<Embedding> embeddings = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();
        int skippedCount = 0;
        for (int i = 0; i < blocks.length; i++) {
            String block = blocks[i] == null ? "" : blocks[i].trim();
            if (block.isEmpty()) {
                continue;
            }
            TextSegment segment = parseBlock(block, i + 1);
            if (segment == null) {
                skippedCount++;
                continue;
            }
            Embedding embedding = aiModelHolder.getEmbeddingModel().embed(segment.text()).content();
            embeddings.add(embedding);
            segments.add(segment);
        }
        if (!embeddings.isEmpty()) {
            embeddingStore.addAll(embeddings, segments);
            ojKnowledgeRetriever.clearRagCache();
        }
        int importedCount = embeddings.size();
        log.info("[Knowledge Init] import finished, imported={}, skipped={}", importedCount, skippedCount);
        return importedCount;
    }

    private String loadClasspathContent(String filePath) {
        try {
            ClassPathResource resource = new ClassPathResource(filePath);
            if (!resource.exists()) {
                log.warn("[Knowledge Init] classpath resource not found: {}", filePath);
                return null;
            }
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[Knowledge Init] load failed, filePath={}", filePath, e);
            return null;
        }
    }

    private boolean hasKnowledgeData() {
        try {
            Embedding probe = aiModelHolder.getEmbeddingModel().embed("knowledge init probe").content();
            List<EmbeddingMatch<TextSegment>> existing = embeddingStore.search(
                    EmbeddingSearchRequest.builder()
                            .queryEmbedding(probe)
                            .maxResults(1)
                            .minScore(0.0)
                            .build()
            ).matches();
            return existing != null && !existing.isEmpty();
        } catch (Exception e) {
            log.warn("[Knowledge Init] failed to probe knowledge collection, fallback to import attempt", e);
            return false;
        }
    }

    private TextSegment parseBlock(String block, int blockIndex) {
        List<String> lines = Arrays.stream(block.split("\\R", -1))
                .map(line -> line == null ? "" : line.trim())
                .collect(Collectors.toList());
        if (lines.size() < 4) {
            log.warn("[Knowledge Init] skip block {} because it is too short", blockIndex);
            return null;
        }

        Map<String, String> metadataMap = new HashMap<>(4);
        int cursor = 0;
        while (cursor < lines.size()) {
            String line = lines.get(cursor);
            if (line.isEmpty()) {
                cursor++;
                break;
            }
            int separatorIndex = line.indexOf(':');
            if (separatorIndex < 0) {
                log.warn("[Knowledge Init] skip block {} because metadata line is invalid: {}", blockIndex, line);
                return null;
            }
            String key = line.substring(0, separatorIndex).trim();
            String value = line.substring(separatorIndex + 1).trim();
            metadataMap.put(key, value);
            cursor++;
        }

        String contentType = metadataMap.get("content_type");
        String tag = metadataMap.get("tag");
        String title = metadataMap.get("title");
        if (isBlank(contentType) || isBlank(tag) || isBlank(title)) {
            log.warn("[Knowledge Init] skip block {} because metadata is incomplete: {}", blockIndex, metadataMap);
            return null;
        }

        String body = lines.subList(cursor, lines.size()).stream()
                .collect(Collectors.joining("\n"))
                .trim();
        if (body.isBlank()) {
            log.warn("[Knowledge Init] skip block {} because body is blank", blockIndex);
            return null;
        }

        if (body.length() < 80 || body.length() > 1200) {
            log.warn("[Knowledge Init] block {} length={} is outside recommended range [80, 1200]",
                    blockIndex, body.length());
        }

        Map<String, Object> segmentMetadata = new HashMap<>(8);
        segmentMetadata.put("content_type", contentType);
        segmentMetadata.put("tag", tag);
        segmentMetadata.put("title", title);

        String imageUrls = metadataMap.get("image_urls");
        if (imageUrls != null && !imageUrls.isBlank()) {
            segmentMetadata.put("image_urls", imageUrls);
        }
        String sourceType = metadataMap.get("source_type");
        if (sourceType != null && !sourceType.isBlank()) {
            segmentMetadata.put("source_type", sourceType);
        }

        String fullText = buildSearchableText(title, tag, contentType, sourceType, imageUrls, body);
        return TextSegment.from(fullText, Metadata.from(segmentMetadata));
    }

    private String buildSearchableText(String title,
                                       String tag,
                                       String contentType,
                                       String sourceType,
                                       String imageUrls,
                                       String body) {
        StringBuilder text = new StringBuilder();
        text.append("title: ").append(title).append("\n");
        text.append("tag: ").append(tag).append("\n");
        text.append("content_type: ").append(contentType).append("\n");
        if (sourceType != null && !sourceType.isBlank()) {
            text.append("source_type: ").append(sourceType).append("\n");
        }
        if (imageUrls != null && !imageUrls.isBlank()) {
            text.append("has_images: true\n");
            text.append("image_urls: ").append(imageUrls).append("\n");
        }
        text.append("\n").append(body);
        return text.toString();
    }

    public void validateImportedCount(int importedCount) {
        if (importedCount <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "未解析到可导入的知识条目");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
