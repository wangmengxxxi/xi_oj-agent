package com.XI.xi_oj.ai.rag.parser;

import com.XI.xi_oj.common.ErrorCode;
import com.XI.xi_oj.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class PdfDocumentParser implements DocumentParser {

    private static final int MAX_CHUNK_LENGTH = 1000;
    private static final int MIN_CHUNK_LENGTH = 80;
    private static final int MAX_TITLE_LENGTH = 60;

    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "^(" +
                    "第[一二三四五六七八九十百千\\d]+[章节篇]" +
                    "|\\d+\\.\\d+(\\.\\d+)?\\s+" +
                    "|[一二三四五六七八九十]+[、.]\\s*" +
                    "|#{1,3}\\s" +
                    ")(.+)$"
    );

    private static final Map<String, String> TAG_KEYWORDS = Map.ofEntries(
            Map.entry("排序", "排序算法"),
            Map.entry("二分", "二分查找"),
            Map.entry("动态规划", "动态规划"),
            Map.entry("dp", "动态规划"),
            Map.entry("贪心", "贪心算法"),
            Map.entry("回溯", "回溯算法"),
            Map.entry("递归", "递归"),
            Map.entry("链表", "链表"),
            Map.entry("二叉树", "二叉树"),
            Map.entry("树", "树"),
            Map.entry("图", "图论"),
            Map.entry("栈", "栈"),
            Map.entry("队列", "队列"),
            Map.entry("哈希", "哈希表"),
            Map.entry("堆", "堆"),
            Map.entry("字符串", "字符串"),
            Map.entry("数组", "数组"),
            Map.entry("搜索", "搜索算法"),
            Map.entry("分治", "分治算法")
    );

    @Override
    public boolean supports(String extension) {
        return "pdf".equalsIgnoreCase(extension);
    }

    @Override
    public String parse(InputStream inputStream, String filename) {
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            String fullText = extractText(document);
            if (fullText == null || fullText.isBlank()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "该 PDF 为扫描版或无文本内容，暂不支持导入");
            }
            List<ChunkResult> chunks = smartChunk(fullText);
            if (chunks.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "PDF 解析后未生成有效知识块");
            }
            log.info("[PDF Parser] file={}, chunks={}", filename, chunks.size());
            return buildMarkdownBlocks(chunks);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("[PDF Parser] parse failed, file={}", filename, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "PDF 解析失败: " + e.getMessage());
        }
    }

    private String extractText(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        return stripper.getText(document);
    }

    private List<ChunkResult> smartChunk(String fullText) {
        String[] lines = fullText.split("\\R");
        List<ChunkResult> chunks = new ArrayList<>();
        StringBuilder currentBody = new StringBuilder();
        String currentTitle = null;
        String currentTag = null;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (isTitle(trimmed)) {
                flushChunk(chunks, currentTitle, currentTag, currentBody);
                currentBody.setLength(0);
                currentTitle = trimmed;
                currentTag = inferTag(trimmed);
                continue;
            }

            currentBody.append(trimmed).append("\n");

            if (currentBody.length() > MAX_CHUNK_LENGTH) {
                flushChunk(chunks, currentTitle, currentTag, currentBody);
                currentBody.setLength(0);
            }
        }

        flushChunk(chunks, currentTitle, currentTag, currentBody);
        return chunks;
    }

    private boolean isTitle(String line) {
        if (line.length() > MAX_TITLE_LENGTH) return false;
        Matcher matcher = TITLE_PATTERN.matcher(line);
        return matcher.matches();
    }

    private void flushChunk(List<ChunkResult> chunks, String title,
                            String tag, StringBuilder body) {
        if (body.length() < MIN_CHUNK_LENGTH) return;
        chunks.add(new ChunkResult(
                title != null ? title : "未分类知识点",
                tag != null ? tag : "算法基础",
                body.toString()
        ));
    }

    private String inferTag(String title) {
        String lower = title.toLowerCase();
        for (Map.Entry<String, String> entry : TAG_KEYWORDS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "算法基础";
    }

    private String buildMarkdownBlocks(List<ChunkResult> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            ChunkResult chunk = chunks.get(i);
            if (i > 0) sb.append("\n---\n");
            sb.append("content_type: 知识点\n");
            sb.append("tag: ").append(chunk.getTag()).append("\n");
            sb.append("title: ").append(chunk.getTitle()).append("\n");
            sb.append("\n");
            sb.append(chunk.getBody().trim());
        }
        return sb.toString();
    }

    @Data
    @AllArgsConstructor
    private static class ChunkResult {
        private String title;
        private String tag;
        private String body;
    }
}
