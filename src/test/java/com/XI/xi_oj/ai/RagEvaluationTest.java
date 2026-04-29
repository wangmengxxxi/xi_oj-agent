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

        printComparisonTable(baseline, withRewrite, withAll);
        printDetailedResults(cases, baseline, withRewrite, withAll);
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

            List<Long> retrievedDocIds = merged.stream()
                    .map(this::extractDocId)
                    .filter(id -> id != null)
                    .toList();

            double keywordHitRate = calcKeywordHitRate(resultTexts, evalCase.expectedKeywords);
            double recall = calcRecall(retrievedDocIds, evalCase.expectedDocIds, TOP_K);
            double mrr = calcMrr(retrievedDocIds, evalCase.expectedDocIds);

            results.add(new CaseResult(query, actualQuery, resultTexts, retrievedDocIds,
                    keywordHitRate, recall, mrr, merged.size()));
        }

        double avgKeywordHit = results.stream().mapToDouble(r -> r.keywordHitRate).average().orElse(0);
        double avgResultCount = results.stream().mapToDouble(r -> r.resultCount).average().orElse(0);
        double rewriteRate = cases.isEmpty() ? 0 : (double) rewriteCount / cases.size();

        boolean hasDocIds = cases.stream().anyMatch(c -> c.expectedDocIds != null && !c.expectedDocIds.isEmpty());
        double avgRecall = hasDocIds ? results.stream().mapToDouble(r -> r.recall).average().orElse(0) : -1;
        double avgMrr = hasDocIds ? results.stream().mapToDouble(r -> r.mrr).average().orElse(0) : -1;

        System.out.printf("[%s] 完成评估: avgKeywordHit=%.2f, avgResults=%.1f, rewriteRate=%.0f%%",
                label, avgKeywordHit, avgResultCount, rewriteRate * 100);
        if (hasDocIds) {
            System.out.printf(", Recall@%d=%.2f, MRR=%.2f", TOP_K, avgRecall, avgMrr);
        }
        System.out.println();

        return new EvalResult(label, results, avgKeywordHit, avgResultCount, rewriteRate, avgRecall, avgMrr);
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

    private void printComparisonTable(EvalResult baseline, EvalResult withRewrite, EvalResult withAll) {
        System.out.println();
        System.out.println("╔══════════════════════════════╦══════════════╦══════════════╦══════════════╗");
        System.out.println("║ 指标                         ║ 基线         ║ +Rewrite     ║ +Rewrite+RR  ║");
        System.out.println("╠══════════════════════════════╬══════════════╬══════════════╬══════════════╣");
        System.out.printf( "║ Keyword Hit Rate             ║    %.2f       ║    %.2f       ║    %.2f       ║%n",
                baseline.avgKeywordHit, withRewrite.avgKeywordHit, withAll.avgKeywordHit);
        System.out.printf( "║ Avg Result Count             ║    %.1f        ║    %.1f        ║    %.1f        ║%n",
                baseline.avgResultCount, withRewrite.avgResultCount, withAll.avgResultCount);
        System.out.printf( "║ Rewrite Rate                 ║    %.0f%%        ║    %.0f%%       ║    %.0f%%       ║%n",
                baseline.rewriteRate * 100, withRewrite.rewriteRate * 100, withAll.rewriteRate * 100);
        if (baseline.avgRecall >= 0) {
            System.out.printf("║ Recall@%d                     ║    %.2f       ║    %.2f       ║    %.2f       ║%n",
                    TOP_K, baseline.avgRecall, withRewrite.avgRecall, withAll.avgRecall);
            System.out.printf("║ MRR                          ║    %.2f       ║    %.2f       ║    %.2f       ║%n",
                    baseline.avgMrr, withRewrite.avgMrr, withAll.avgMrr);
        } else {
            System.out.println("║ Recall@K / MRR               ║  (需填写 expectedDocIds 后启用)                ║");
        }
        System.out.println("╚══════════════════════════════╩══════════════╩══════════════╩══════════════╝");
        System.out.println();
    }

    private void printDetailedResults(List<EvalCase> cases, EvalResult baseline, EvalResult withRewrite, EvalResult withAll) {
        System.out.println("=== 逐条详情 ===");
        for (int i = 0; i < cases.size(); i++) {
            EvalCase ec = cases.get(i);
            CaseResult br = baseline.results.get(i);
            CaseResult wr = withRewrite.results.get(i);
            CaseResult ar = withAll.results.get(i);

            System.out.printf("%n--- [%d] %s (分类: %s) ---%n", i + 1, ec.query, ec.category);
            if (!wr.actualQuery.equals(ec.query)) {
                System.out.printf("  改写: %s -> %s%n", ec.query, wr.actualQuery);
            }
            System.out.printf("  期望关键词: %s%n", ec.expectedKeywords);
            System.out.printf("  Keyword Hit: 基线=%.2f  +Rewrite=%.2f  +All=%.2f%n",
                    br.keywordHitRate, wr.keywordHitRate, ar.keywordHitRate);
            System.out.printf("  结果数:      基线=%d     +Rewrite=%d     +All=%d%n",
                    br.resultCount, wr.resultCount, ar.resultCount);
            if (ec.expectedDocIds != null && !ec.expectedDocIds.isEmpty()) {
                System.out.printf("  Recall@%d:   基线=%.2f  +Rewrite=%.2f  +All=%.2f%n",
                        TOP_K, br.recall, wr.recall, ar.recall);
                System.out.printf("  MRR:         基线=%.2f  +Rewrite=%.2f  +All=%.2f%n",
                        br.mrr, wr.mrr, ar.mrr);
            }

            if (!ar.resultTexts.isEmpty()) {
                System.out.println("  Top1 结果片段: " + truncate(ar.resultTexts.get(0), 100));
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
                    obj.getJSONArray("expectedKeywords").toList(String.class),
                    obj.getJSONArray("expectedDocIds").toList(Long.class),
                    obj.getStr("category", "")
            ));
        }
        return cases;
    }

    record EvalCase(String query, List<String> expectedKeywords, List<Long> expectedDocIds, String category) {}

    record CaseResult(String query, String actualQuery, List<String> resultTexts,
                      List<Long> retrievedDocIds, double keywordHitRate,
                      double recall, double mrr, int resultCount) {}

    static class EvalResult {
        final String label;
        final List<CaseResult> results;
        final double avgKeywordHit;
        final double avgResultCount;
        final double rewriteRate;
        final double avgRecall;
        final double avgMrr;

        EvalResult(String label, List<CaseResult> results) {
            this(label, results, 0, 0, 0, -1, -1);
        }

        EvalResult(String label, List<CaseResult> results, double avgKeywordHit,
                   double avgResultCount, double rewriteRate, double avgRecall, double avgMrr) {
            this.label = label;
            this.results = results;
            this.avgKeywordHit = avgKeywordHit;
            this.avgResultCount = avgResultCount;
            this.rewriteRate = rewriteRate;
            this.avgRecall = avgRecall;
            this.avgMrr = avgMrr;
        }
    }
}
