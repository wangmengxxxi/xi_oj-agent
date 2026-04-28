package com.XI.xi_oj.ai.rag.parser;

import java.io.InputStream;

/**
 * 文档解析器接口，将不同格式的文档转换为 markdown block 格式字符串
 */
public interface DocumentParser {

    /**
     * 解析文档，输出 markdown block 格式字符串（可直接传入 KnowledgeInitializer.parseAndStore）
     */
    String parse(InputStream inputStream, String filename);

    boolean supports(String extension);
}
