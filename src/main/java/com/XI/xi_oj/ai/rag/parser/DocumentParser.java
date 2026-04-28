package com.XI.xi_oj.ai.rag.parser;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/**
 * 文档解析器接口，将不同格式的文档转换为 markdown block 格式字符串
 */
public interface DocumentParser {

    /**
     * 解析文档，输出 markdown block 格式字符串（可直接传入 KnowledgeInitializer.parseAndStore）
     */
    String parse(InputStream inputStream, String filename);

    boolean supports(String extension);

    /**
     * 解析文档并提取图片，返回 markdown blocks + 图片 URL 列表。
     * 默认实现不提取图片，子类可 override。
     */
    default ParseResult parseWithImages(InputStream inputStream, String filename) {
        return new ParseResult(parse(inputStream, filename), Collections.emptyList());
    }

    record ParseResult(String markdownBlocks, List<String> imageUrls) {}
}
