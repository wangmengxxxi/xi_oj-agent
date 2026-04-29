package com.XI.xi_oj.ai.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.DefaultContentAggregator;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class RerankingContentAggregator implements ContentAggregator {

    private final RerankService rerankService;
    private final int fallbackTopN;
    private final DefaultContentAggregator delegate = new DefaultContentAggregator();

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        List<Content> merged = delegate.aggregate(queryToContents);
        String query = queryToContents.keySet().stream()
                .findFirst()
                .map(Query::text)
                .orElse("");
        return rerankService.rerank(query, merged, rerankService.topN(fallbackTopN));
    }
}
