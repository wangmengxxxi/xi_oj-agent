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
                .map(this::enrichWithImages)
                .toList();
    }

    private Content enrichWithImages(Content content) {
        TextSegment segment = content.textSegment();
        if (segment == null) {
            return content;
        }
        String imageUrls = segment.metadata().getString("image_urls");
        if (imageUrls == null || imageUrls.isBlank()) {
            return content;
        }

        StringBuilder enriched = new StringBuilder(segment.text());
        enriched.append("\n\n[RAG_SOURCE_IMAGES]");
        enriched.append("\nThe following Markdown images belong to this retrieved knowledge segment. ");
        enriched.append("If they are relevant to the user's question, keep these image links in the answer.");
        for (String url : imageUrls.split(",")) {
            String trimmed = url.trim();
            if (!trimmed.isEmpty()) {
                enriched.append("\n![knowledge-image](").append(trimmed).append(")");
            }
        }
        return Content.from(TextSegment.from(enriched.toString(), segment.metadata()));
    }
}
