package com.XI.xi_oj.ai.observability;

public final class AiObservationEventType {

    public static final String AI_CALL = "AI_CALL";
    public static final String AI_RATE_LIMITED = "AI_RATE_LIMITED";
    public static final String RAG_EMPTY = "RAG_EMPTY";
    public static final String RERANK_CALL = "RERANK_CALL";
    public static final String RERANK_FAILED = "RERANK_FAILED";
    public static final String LINK_REMOVED = "LINK_REMOVED";

    private AiObservationEventType() {
    }
}
