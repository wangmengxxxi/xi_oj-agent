package com.XI.xi_oj.ai.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.DefaultContentAggregator;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;

import java.util.*;

@RequiredArgsConstructor
public class RerankingContentAggregator implements ContentAggregator {

    private final RerankService rerankService;
    private final int fallbackTopN;
    private final DefaultContentAggregator delegate = new DefaultContentAggregator();

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        List<Content> merged = delegate.aggregate(queryToContents);

        List<Content> deduped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Content c : merged) {
            String text = c.textSegment() != null ? c.textSegment().text() : "";
            if (seen.add(text)) {
                deduped.add(c);
            }
        }

        String originalQuery = QueryRewriteTransformer.ORIGINAL_QUERY.get();
        String rerankQuery = originalQuery != null ? originalQuery : queryToContents.keySet().stream()
                .findFirst()
                .map(Query::text)
                .orElse("");

        return rerankService.rerank(rerankQuery, deduped, rerankService.topN(fallbackTopN));
    }
}
