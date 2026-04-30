package com.XI.xi_oj.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.XI.xi_oj.ai.agent.AiModelHolder;
import com.XI.xi_oj.ai.rag.QueryRewriter;
import com.XI.xi_oj.ai.rag.RerankService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * RAG 离线评估测试
 *
 * 评估三种配置下的检索质量：
 * 1. 基线（无 Query Rewrite，无 Rerank）
 * 2. + Query Rewrite
 * 3. + Query Rewrite + Rerank
 *
 * 指标：
 * - Keyword Hit Rate：检索结果中命中期望关键词的比例
 * - Title Hit Rate：检索结果标题命中期望标题词的比例
 * - Answer Point Hit Rate：检索结果正文覆盖期望答案要点的比例
 * - Recall@K：检索结果中命中期望文档的比例（需填写 expectedDocIds）
 * - MRR：首个命中文档的排名倒数（需填写 expectedDocIds）
 * - Avg Result Count：平均返回结果数
 * - Rewrite Rate：Query 被改写的比例
 *
 * 使用方式：
 * 1. 确保 Milvus 和 Redis 已启动，知识库已导入
 * 2. 运行此测试，查看控制台输出的对比表
 * 3. 根据实际检索结果，在 rag_eval_cases.json 中补充 expectedDocIds 后可启用 Recall@K / MRR 评估
 */
@SpringBootTest
public class RagEvaluationTest {

    @Resource
    private AiModelHolder aiModelHolder;

    @Resource
    private QueryRewriter queryRewriter;

    @Resource
    private RerankService rerankService;

    @Resource
    @Qualifier("embeddingStore")
    private MilvusEmbeddingStore embeddingStore;

    @Resource
    @Qualifier("questionEmbeddingStore")
    private MilvusEmbeddingStore questionEmbeddingStore;

    private static final int TOP_K = 5;
    private static final double MIN_SCORE = 0.5;

    @Test
    public void evaluateRagPipeline() throws Exception {
        List<EvalCase> cases = loadEvalCases();
        System.out.println("=== RAG 离线评估 ===");
        System.out.println("评估用例数: " + cases.size());
        System.out.println("TopK: " + TOP_K + ", MinScore: " + MIN_SCORE);
        System.out.println();

        EvalResult baseline = runEvaluation(cases, false, false, "基线（无优化）");
        EvalResult withRewrite = runEvaluation(cases, true, false, "+ Query Rewrite");
        EvalResult withAll = runEvaluation(cases, true, true, "+ Rewrite + Rerank");
        EvalResult hybrid = runHybridEvaluation(cases, "+ Hybrid + Rerank");

        printComparisonTable(baseline, withRewrite, withAll, hybrid);
        printDetailedResults(cases, baseline, withRewrite, withAll, hybrid);
    }

    private EvalResult runEvaluation(List<EvalCase> cases, boolean useRewrite, boolean useRerank, String label) {
        EmbeddingModel em = aiModelHolder.getEmbeddingModel();
        if (em == null) {
            System.out.println("[SKIP] EmbeddingModel 未初始化，请先配置 API Key");
            return new EvalResult(label, List.of());
        }

        List<CaseResult> results = new ArrayList<>();
        int rewriteCount = 0;

        for (EvalCase evalCase : cases) {
            String query = evalCase.query;
            String actualQuery = query;

            if (useRewrite) {
                actualQuery = queryRewriter.rewrite(query);
                if (!actualQuery.equals(query)) {
                    rewriteCount++;
                }
            }

            List<Content> knowledgeResults = searchStore(embeddingStore, em, actualQuery, TOP_K);
            List<Content> questionResults = searchStore(questionEmbeddingStore, em, actualQuery, TOP_K);

            List<Content> merged = new ArrayList<>();
            merged.addAll(knowledgeResults);
            merged.addAll(questionResults);

            if (useRerank && rerankService.enabled() && merged.size() > 1) {
                merged = rerankService.rerank(actualQuery, merged, TOP_K);
            } else if (merged.size() > TOP_K) {
                merged = merged.subList(0, TOP_K);
            }

            List<String> resultTexts = merged.stream()
                    .map(c -> c.textSegment() != null ? c.textSegment().text() : "")
                    .toList();
            List<String> resultTitles = merged.stream()
                    .map(this::extractTitle)
                    .toList();

            List<Long> retrievedDocIds = merged.stream()
                    .map(this::extractDocId)
                    .filter(id -> id != null)
                    .toList();

            double keywordHitRate = calcKeywordHitRate(resultTexts, evalCase.expectedKeywords);
            double titleHitRate = calcTitleHitRate(resultTitles, evalCase.expectedTitles);
            double answerPointHitRate = calcAnswerPointHitRate(resultTexts, evalCase.expectedAnswerPoints);
            double recall = calcRecall(retrievedDocIds, evalCase.expectedDocIds, TOP_K);
            double mrr = calcMrr(retrievedDocIds, evalCase.expectedDocIds);

            results.add(new CaseResult(query, actualQuery, resultTexts, resultTitles, retrievedDocIds,
                    keywordHitRate, titleHitRate, answerPointHitRate, recall, mrr, merged.size()));
        }

        double avgKeywordHit = results.stream().mapToDouble(r -> r.keywordHitRate).average().orElse(0);
        double avgTitleHit = results.stream().mapToDouble(r -> r.titleHitRate).average().orElse(0);
        double avgAnswerPointHit = results.stream().mapToDouble(r -> r.answerPointHitRate).average().orElse(0);
        double avgResultCount = results.stream().mapToDouble(r -> r.resultCount).average().orElse(0);
        double rewriteRate = cases.isEmpty() ? 0 : (double) rewriteCount / cases.size();

        boolean hasDocIds = cases.stream().anyMatch(c -> c.expectedDocIds != null && !c.expectedDocIds.isEmpty());
        double avgRecall = hasDocIds ? results.stream().mapToDouble(r -> r.recall).average().orElse(0) : -1;
        double avgMrr = hasDocIds ? results.stream().mapToDouble(r -> r.mrr).average().orElse(0) : -1;

        System.out.printf("[%s] 完成评估: avgKeywordHit=%.2f, avgTitleHit=%.2f, avgAnswerPointHit=%.2f, avgResults=%.1f, rewriteRate=%.0f%%",
                label, avgKeywordHit, avgTitleHit, avgAnswerPointHit, avgResultCount, rewriteRate * 100);
        if (hasDocIds) {
            System.out.printf(", Recall@%d=%.2f, MRR=%.2f", TOP_K, avgRecall, avgMrr);
        }
        System.out.println();

        return new EvalResult(label, results, avgKeywordHit, avgTitleHit, avgAnswerPointHit,
                avgResultCount, rewriteRate, avgRecall, avgMrr);
    }

    private EvalResult runHybridEvaluation(List<EvalCase> cases, String label) {
        EmbeddingModel em = aiModelHolder.getEmbeddingModel();
        if (em == null) {
            System.out.println("[SKIP] EmbeddingModel 未初始化，请先配置 API Key");
            return new EvalResult(label, List.of());
        }

        List<CaseResult> results = new ArrayList<>();

        for (EvalCase evalCase : cases) {
            String originalQuery = evalCase.query;
            String rewrittenQuery = queryRewriter.rewrite(originalQuery);
            if (rewrittenQuery == null || rewrittenQuery.isBlank()) {
                rewrittenQuery = originalQuery;
            }

            List<Content> originalKnowledge = searchStore(embeddingStore, em, originalQuery, TOP_K);
            List<Content> originalQuestion = searchStore(questionEmbeddingStore, em, originalQuery, TOP_K);
            List<Content> merged = new ArrayList<>(originalKnowledge);
            merged.addAll(originalQuestion);

            if (!rewrittenQuery.equals(originalQuery)) {
                List<Content> rewriteKnowledge = searchStore(embeddingStore, em, rewrittenQuery, TOP_K);
                List<Content> rewriteQuestion = searchStore(questionEmbeddingStore, em, rewrittenQuery, TOP_K);
                merged.addAll(rewriteKnowledge);
                merged.addAll(rewriteQuestion);
            }

            List<Content> deduped = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Content c : merged) {
                String text = c.textSegment() != null ? c.textSegment().text() : "";
                if (seen.add(text)) {
                    deduped.add(c);
                }
            }

            if (rerankService.enabled() && deduped.size() > 1) {
                deduped = rerankService.rerank(originalQuery, deduped, TOP_K);
            } else if (deduped.size() > TOP_K) {
                deduped = deduped.subList(0, TOP_K);
            }

            List<String> resultTexts = deduped.stream()
                    .map(c -> c.textSegment() != null ? c.textSegment().text() : "")
                    .toList();
            List<String> resultTitles = deduped.stream()
                    .map(this::extractTitle)
                    .toList();

            List<Long> retrievedDocIds = deduped.stream()
                    .map(this::extractDocId)
                    .filter(id -> id != null)
                    .toList();

            double keywordHitRate = calcKeywordHitRate(resultTexts, evalCase.expectedKeywords);
            double titleHitRate = calcTitleHitRate(resultTitles, evalCase.expectedTitles);
            double answerPointHitRate = calcAnswerPointHitRate(resultTexts, evalCase.expectedAnswerPoints);
            double recall = calcRecall(retrievedDocIds, evalCase.expectedDocIds, TOP_K);
            double mrr = calcMrr(retrievedDocIds, evalCase.expectedDocIds);

            results.add(new CaseResult(originalQuery, rewrittenQuery, resultTexts, resultTitles, retrievedDocIds,
                    keywordHitRate, titleHitRate, answerPointHitRate, recall, mrr, deduped.size()));
        }

        double avgKeywordHit = results.stream().mapToDouble(r -> r.keywordHitRate).average().orElse(0);
        double avgTitleHit = results.stream().mapToDouble(r -> r.titleHitRate).average().orElse(0);
        double avgAnswerPointHit = results.stream().mapToDouble(r -> r.answerPointHitRate).average().orElse(0);
        double avgResultCount = results.stream().mapToDouble(r -> r.resultCount).average().orElse(0);
        double rewriteRate = cases.isEmpty() ? 0 : (double) results.stream()
                .filter(r -> !r.actualQuery.equals(r.query)).count() / cases.size();

        boolean hasDocIds = cases.stream().anyMatch(c -> c.expectedDocIds != null && !c.expectedDocIds.isEmpty());
        double avgRecall = hasDocIds ? results.stream().mapToDouble(r -> r.recall).average().orElse(0) : -1;
        double avgMrr = hasDocIds ? results.stream().mapToDouble(r -> r.mrr).average().orElse(0) : -1;

        System.out.printf("[%s] 完成评估: avgKeywordHit=%.2f, avgTitleHit=%.2f, avgAnswerPointHit=%.2f, avgResults=%.1f, rewriteRate=%.0f%%",
                label, avgKeywordHit, avgTitleHit, avgAnswerPointHit, avgResultCount, rewriteRate * 100);
        if (hasDocIds) {
            System.out.printf(", Recall@%d=%.2f, MRR=%.2f", TOP_K, avgRecall, avgMrr);
        }
        System.out.println();

        return new EvalResult(label, results, avgKeywordHit, avgTitleHit, avgAnswerPointHit,
                avgResultCount, rewriteRate, avgRecall, avgMrr);
    }

    private List<Content> searchStore(MilvusEmbeddingStore store, EmbeddingModel em, String query, int topK) {
        try {
            var queryEmbedding = em.embed(query).content();
            var searchResult = store.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(topK)
                    .minScore(MIN_SCORE)
                    .build());
            return searchResult.matches().stream()
                    .filter(m -> m.embedded() != null)
                    .map(m -> Content.from(m.embedded()))
                    .toList();
        } catch (Exception e) {
            System.out.println("[WARN] 检索失败: " + e.getMessage());
            return List.of();
        }
    }

    private double calcKeywordHitRate(List<String> results, List<String> expectedKeywords) {
        if (expectedKeywords == null || expectedKeywords.isEmpty()) {
            return 0;
        }
        String combined = String.join(" ", results).toLowerCase();
        long hits = expectedKeywords.stream()
                .filter(kw -> combined.contains(kw.toLowerCase()))
                .count();
        return (double) hits / expectedKeywords.size();
    }

    private double calcTitleHitRate(List<String> resultTitles, List<String> expectedTitles) {
        if (expectedTitles == null || expectedTitles.isEmpty()) {
            return 0;
        }
        String combined = String.join(" ", resultTitles).toLowerCase();
        long hits = expectedTitles.stream()
                .filter(title -> combined.contains(title.toLowerCase()))
                .count();
        return (double) hits / expectedTitles.size();
    }

    private double calcAnswerPointHitRate(List<String> results, List<String> expectedAnswerPoints) {
        if (expectedAnswerPoints == null || expectedAnswerPoints.isEmpty()) {
            return 0;
        }
        String combined = String.join(" ", results).toLowerCase();
        long hits = expectedAnswerPoints.stream()
                .filter(point -> combined.contains(point.toLowerCase()))
                .count();
        return (double) hits / expectedAnswerPoints.size();
    }

    private String extractTitle(Content content) {
        if (content == null || content.textSegment() == null) {
            return "";
        }
        var metadata = content.textSegment().metadata();
        if (metadata == null) {
            return "";
        }
        String title = metadata.getString("title");
        if (title != null && !title.isBlank()) {
            return title;
        }
        String questionTitle = metadata.getString("question_title");
        return questionTitle != null ? questionTitle : "";
    }

    private Long extractDocId(Content content) {
        if (content == null || content.textSegment() == null) {
            return null;
        }
        var metadata = content.textSegment().metadata();
        if (metadata == null) {
            return null;
        }
        Long qid = metadata.getLong("question_id");
        if (qid != null) {
            return qid;
        }
        String title = metadata.getString("title");
        if (title != null) {
            return (long) title.hashCode();
        }
        return null;
    }

    private double calcRecall(List<Long> retrievedIds, List<Long> expectedIds, int k) {
        if (expectedIds == null || expectedIds.isEmpty()) {
            return 0;
        }
        Set<Long> topK = new HashSet<>(retrievedIds.subList(0, Math.min(k, retrievedIds.size())));
        long hits = expectedIds.stream().filter(topK::contains).count();
        return (double) hits / expectedIds.size();
    }

    private double calcMrr(List<Long> retrievedIds, List<Long> expectedIds) {
        if (expectedIds == null || expectedIds.isEmpty()) {
            return 0;
        }
        Set<Long> expected = new HashSet<>(expectedIds);
        for (int i = 0; i < retrievedIds.size(); i++) {
            if (expected.contains(retrievedIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0;
    }

    private void printComparisonTable(EvalResult baseline, EvalResult withRewrite, EvalResult withAll, EvalResult hybrid) {
        System.out.println();
        System.out.println("╔══════════════════════════════╦══════════════╦══════════════╦══════════════╦══════════════╗");
        System.out.println("║ 指标                         ║ 基线         ║ +Rewrite     ║ +Rewrite+RR  ║ +Hybrid+RR   ║");
        System.out.println("╠══════════════════════════════╬══════════════╬══════════════╬══════════════╬══════════════╣");
        System.out.printf( "║ Keyword Hit Rate             ║    %.2f       ║    %.2f       ║    %.2f       ║    %.2f       ║%n",
                baseline.avgKeywordHit, withRewrite.avgKeywordHit, withAll.avgKeywordHit, hybrid.avgKeywordHit);
        System.out.printf( "║ Title Hit Rate               ║    %.2f       ║    %.2f       ║    %.2f       ║    %.2f       ║%n",
                baseline.avgTitleHit, withRewrite.avgTitleHit, withAll.avgTitleHit, hybrid.avgTitleHit);
        System.out.printf( "║ Answer Point Hit Rate        ║    %.2f       ║    %.2f       ║    %.2f       ║    %.2f       ║%n",
                baseline.avgAnswerPointHit, withRewrite.avgAnswerPointHit, withAll.avgAnswerPointHit, hybrid.avgAnswerPointHit);
        System.out.printf( "║ Avg Result Count             ║    %.1f        ║    %.1f        ║    %.1f        ║    %.1f        ║%n",
                baseline.avgResultCount, withRewrite.avgResultCount, withAll.avgResultCount, hybrid.avgResultCount);
        System.out.printf( "║ Rewrite Rate                 ║    %.0f%%        ║    %.0f%%       ║    %.0f%%       ║    %.0f%%       ║%n",
                baseline.rewriteRate * 100, withRewrite.rewriteRate * 100, withAll.rewriteRate * 100, hybrid.rewriteRate * 100);
        if (baseline.avgRecall >= 0) {
            System.out.printf("║ Recall@%d                     ║    %.2f       ║    %.2f       ║    %.2f       ║    %.2f       ║%n",
                    TOP_K, baseline.avgRecall, withRewrite.avgRecall, withAll.avgRecall, hybrid.avgRecall);
            System.out.printf("║ MRR                          ║    %.2f       ║    %.2f       ║    %.2f       ║    %.2f       ║%n",
                    baseline.avgMrr, withRewrite.avgMrr, withAll.avgMrr, hybrid.avgMrr);
        } else {
            System.out.println("║ Recall@K / MRR               ║  (需填写 expectedDocIds 后启用)                               ║");
        }
        System.out.println("╚══════════════════════════════╩══════════════╩══════════════╩══════════════╩══════════════╝");
        System.out.println();
    }

    private void printDetailedResults(List<EvalCase> cases, EvalResult baseline, EvalResult withRewrite, EvalResult withAll, EvalResult hybrid) {
        System.out.println("=== 逐条详情 ===");
        for (int i = 0; i < cases.size(); i++) {
            EvalCase ec = cases.get(i);
            CaseResult br = baseline.results.get(i);
            CaseResult wr = withRewrite.results.get(i);
            CaseResult ar = withAll.results.get(i);
            CaseResult hr = hybrid.results.get(i);

            System.out.printf("%n--- [%d] %s (分类: %s) ---%n", i + 1, ec.query, ec.category);
            if (!wr.actualQuery.equals(ec.query)) {
                System.out.printf("  改写: %s -> %s%n", ec.query, wr.actualQuery);
            }
            System.out.printf("  期望关键词: %s%n", ec.expectedKeywords);
            System.out.printf("  期望标题词: %s%n", ec.expectedTitles);
            System.out.printf("  期望答案点: %s%n", ec.expectedAnswerPoints);
            System.out.printf("  Keyword Hit: 基线=%.2f  +Rewrite=%.2f  +All=%.2f  +Hybrid=%.2f%n",
                    br.keywordHitRate, wr.keywordHitRate, ar.keywordHitRate, hr.keywordHitRate);
            System.out.printf("  Title Hit:   基线=%.2f  +Rewrite=%.2f  +All=%.2f  +Hybrid=%.2f%n",
                    br.titleHitRate, wr.titleHitRate, ar.titleHitRate, hr.titleHitRate);
            System.out.printf("  Answer Hit:  基线=%.2f  +Rewrite=%.2f  +All=%.2f  +Hybrid=%.2f%n",
                    br.answerPointHitRate, wr.answerPointHitRate, ar.answerPointHitRate, hr.answerPointHitRate);
            System.out.printf("  结果数:      基线=%d     +Rewrite=%d     +All=%d     +Hybrid=%d%n",
                    br.resultCount, wr.resultCount, ar.resultCount, hr.resultCount);
            if (ec.expectedDocIds != null && !ec.expectedDocIds.isEmpty()) {
                System.out.printf("  Recall@%d:   基线=%.2f  +Rewrite=%.2f  +All=%.2f  +Hybrid=%.2f%n",
                        TOP_K, br.recall, wr.recall, ar.recall, hr.recall);
                System.out.printf("  MRR:         基线=%.2f  +Rewrite=%.2f  +All=%.2f  +Hybrid=%.2f%n",
                        br.mrr, wr.mrr, ar.mrr, hr.mrr);
            }

            if (!hr.resultTexts.isEmpty()) {
                System.out.println("  TopK 标题 (Hybrid): " + hr.resultTitles);
                System.out.println("  Top1 结果片段 (Hybrid): " + truncate(hr.resultTexts.get(0), 100));
            }
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private List<EvalCase> loadEvalCases() throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream("eval/rag_eval_cases.json");
        if (is == null) {
            throw new RuntimeException("找不到 eval/rag_eval_cases.json");
        }
        String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        JSONArray array = JSONUtil.parseArray(json);
        List<EvalCase> cases = new ArrayList<>();
        for (Object item : array) {
            JSONObject obj = (JSONObject) item;
            cases.add(new EvalCase(
                    obj.getStr("query"),
                    readStringList(obj, "expectedKeywords"),
                    readStringList(obj, "expectedTitles"),
                    readStringList(obj, "expectedAnswerPoints"),
                    readLongList(obj, "expectedDocIds"),
                    obj.getStr("category", "")
            ));
        }
        return cases;
    }

    private List<String> readStringList(JSONObject obj, String key) {
        JSONArray array = obj.getJSONArray(key);
        return array == null ? List.of() : array.toList(String.class);
    }

    private List<Long> readLongList(JSONObject obj, String key) {
        JSONArray array = obj.getJSONArray(key);
        return array == null ? List.of() : array.toList(Long.class);
    }

    record EvalCase(String query, List<String> expectedKeywords, List<String> expectedTitles,
                    List<String> expectedAnswerPoints, List<Long> expectedDocIds, String category) {}

    record CaseResult(String query, String actualQuery, List<String> resultTexts, List<String> resultTitles,
                      List<Long> retrievedDocIds, double keywordHitRate, double titleHitRate,
                      double answerPointHitRate,
                      double recall, double mrr, int resultCount) {}

    static class EvalResult {
        final String label;
        final List<CaseResult> results;
        final double avgKeywordHit;
        final double avgTitleHit;
        final double avgAnswerPointHit;
        final double avgResultCount;
        final double rewriteRate;
        final double avgRecall;
        final double avgMrr;

        EvalResult(String label, List<CaseResult> results) {
            this(label, results, 0, 0, 0, 0, 0, -1, -1);
        }

        EvalResult(String label, List<CaseResult> results, double avgKeywordHit, double avgTitleHit,
                   double avgAnswerPointHit,
                   double avgResultCount, double rewriteRate, double avgRecall, double avgMrr) {
            this.label = label;
            this.results = results;
            this.avgKeywordHit = avgKeywordHit;
            this.avgTitleHit = avgTitleHit;
            this.avgAnswerPointHit = avgAnswerPointHit;
            this.avgResultCount = avgResultCount;
            this.rewriteRate = rewriteRate;
            this.avgRecall = avgRecall;
            this.avgMrr = avgMrr;
        }
    }
}
