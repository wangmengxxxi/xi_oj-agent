package com.XI.xi_oj.ai.rag.parser;

public interface ImportProgressCallback {

    void onStep(String stepName, int progressPercent);

    void onImageCaptionProgress(int completed, int total);

    static ImportProgressCallback noop() {
        return NOOP;
    }

    ImportProgressCallback NOOP = new ImportProgressCallback() {
        @Override
        public void onStep(String stepName, int progressPercent) {}

        @Override
        public void onImageCaptionProgress(int completed, int total) {}
    };
}
