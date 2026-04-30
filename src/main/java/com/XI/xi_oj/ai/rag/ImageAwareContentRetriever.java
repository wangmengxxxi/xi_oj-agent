package com.XI.xi_oj.ai.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.List;

public class ImageAwareContentRetriever implements ContentRetriever {

    private final ContentRetriever delegate;

    public ImageAwareContentRetriever(ContentRetriever delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<Content> retrieve(Query query) {
        return delegate.retrieve(query).stream()
                .map(content -> enrichWithImages(content, query.text()))
                .toList();
    }

    private Content enrichWithImages(Content content, String query) {
        TextSegment segment = content.textSegment();
        if (segment == null) {
            return content;
        }
        String enriched = RagImageSupport.appendRelevantImages(segment, query);
        return enriched.equals(segment.text())
                ? content
                : Content.from(TextSegment.from(enriched, segment.metadata()));
    }
}
