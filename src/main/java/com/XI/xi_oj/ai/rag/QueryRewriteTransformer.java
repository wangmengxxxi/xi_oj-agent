package com.XI.xi_oj.ai.rag;

import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.List;

@RequiredArgsConstructor
public class QueryRewriteTransformer implements QueryTransformer {

    public static final ThreadLocal<String> ORIGINAL_QUERY = new ThreadLocal<>();

    private final QueryRewriter queryRewriter;

    @Override
    public Collection<Query> transform(Query query) {
        ORIGINAL_QUERY.set(query.text());

        String rewritten = queryRewriter.rewrite(query.text());
        if (rewritten == null || rewritten.isBlank() || rewritten.equals(query.text())) {
            return List.of(query);
        }
        return List.of(query, Query.from(rewritten, query.metadata()));
    }
}
