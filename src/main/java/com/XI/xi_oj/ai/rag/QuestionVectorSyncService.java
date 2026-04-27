package com.XI.xi_oj.ai.rag;

import com.XI.xi_oj.ai.agent.AiModelHolder;
import com.XI.xi_oj.model.entity.Question;
import com.XI.xi_oj.service.QuestionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 问题向量同步服务类
 * 用于重建和管理问题的向量表示，支持向量检索和相似度计算
 */
@Service
@Slf4j
public class QuestionVectorSyncService {

    @Resource
    private QuestionService questionService;  // 问题服务接口

    @Resource
    private AiModelHolder aiModelHolder;

    @Resource(name = "questionEmbeddingStore")
    private MilvusEmbeddingStore questionEmbeddingStore;  // Milvus向量存储

    @Resource
    private OJKnowledgeRetriever ojKnowledgeRetriever;  // OJ知识检索器

    /**
     * 重建所有问题的向量表示
     * @return 成功重建的题目数量
     */
    public int rebuildQuestionVectors() {
        // 查询所有未删除的问题
        List<Question> questions = questionService.list(new QueryWrapper<Question>()
                .eq("isDelete", 0));

        log.info("[Question Vector] start rebuilding question vectors, count={}", questions.size());
        questionEmbeddingStore.removeAll();  // 清空现有向量存储

        List<Embedding> embeddings = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();
        int fail = 0;
        for (Question question : questions) {
            try {
                String vectorText = buildVectorText(question);
                Embedding embedding = aiModelHolder.getEmbeddingModel().embed(vectorText).content();
                embeddings.add(embedding);
                segments.add(TextSegment.from(vectorText, buildMetadata(question)));
            } catch (Exception e) {
                fail++;
                log.error("[Question Vector] embed failed, questionId={}", question.getId(), e);
            }
        }

        if (!embeddings.isEmpty()) {
            questionEmbeddingStore.addAll(embeddings, segments);
        }
        ojKnowledgeRetriever.clearRagCache();
        int success = embeddings.size();
        log.info("[Question Vector] rebuild finished, success={}, fail={}", success, fail);
        return success;
    }

    /**
     * 构建用于向量化的文本内容
     * @param question 问题对象
     * @return 格式化后的文本内容
     */
    private String buildVectorText(Question question) {
        String thinking = extractThinkingText(question.getAnswer());
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("题目ID：%d\n题目标题：%s\n题目链接：/view/question/%d\n题干：%s\n考点标签：%s",
                question.getId(),
                safe(question.getTitle()),
                question.getId(),
                safe(question.getContent()),
                safe(question.getTags())));
        if (!thinking.isEmpty()) {
            sb.append("\n解题思路：").append(thinking);
        }
        return sb.toString();
    }

    /**
     * 从 answer 字段提取纯文字思路，剥掉代码块
     * answer 格式：## 参考答案 + ```代码``` + ## 思路分析 + 文字说明
     */
    private String extractThinkingText(String answer) {
        if (answer == null || answer.isBlank()) return "";
        String text = answer.replaceAll("```[\\s\\S]*?```", "");
        int idx = text.indexOf("## 思路分析");
        if (idx >= 0) {
            text = text.substring(idx + "## 思路分析".length());
        }
        text = text.replaceAll("##.*", "").trim();
        if (text.length() > 500) text = text.substring(0, 500);
        return text;
    }

    /**
     * 构建问题元数据
     * @param question 问题对象
     * @return 元数据对象
     */
    private Metadata buildMetadata(Question question) {
        Map<String, Object> metadataMap = new HashMap<>();
        metadataMap.put("question_id", question.getId());
        metadataMap.put("content_type", "题目");
        metadataMap.put("tag", safe(question.getTags()));
        metadataMap.put("difficulty", safe(question.getDifficulty()));
        return Metadata.from(metadataMap);
    }

    /**
     * 安全处理字符串值，防止空指针异常
     * @param value 输入字符串
     * @return 处理后的字符串，空值返回空字符串
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }
}
