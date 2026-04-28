package com.XI.xi_oj.ai.rag.parser;

import com.XI.xi_oj.common.ErrorCode;
import com.XI.xi_oj.exception.BusinessException;
import com.XI.xi_oj.manager.MinioService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
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
public class WordDocumentParser implements DocumentParser {

    private static final int MAX_CHUNK_LENGTH = 1000;
    private static final int MIN_CHUNK_LENGTH = 80;
    private static final int MAX_TITLE_LENGTH = 60;
    private static final int MIN_IMAGE_SIZE = 5000;

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

    private final MinioService minioService;

    public WordDocumentParser(MinioService minioService) {
        this.minioService = minioService;
    }

    @Override
    public boolean supports(String extension) {
        return "docx".equalsIgnoreCase(extension);
    }

    @Override
    public String parse(InputStream inputStream, String filename) {
        return parseWithImages(inputStream, filename).markdownBlocks();
    }

    @Override
    public ParseResult parseWithImages(InputStream inputStream, String filename) {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            List<ChunkResult> chunks = parseDocument(document);
            if (chunks.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "Word 文档解析后未生成有效知识块");
            }

            List<String> imageUrls = extractAndUploadImages(document, filename);

            // Word 无法精确按段落关联图片，将所有图片分配给第一个 chunk
            if (!imageUrls.isEmpty() && !chunks.isEmpty()) {
                chunks.get(0).setImageUrls(String.join(",", imageUrls));
            }

            log.info("[Word Parser] file={}, chunks={}, images={}", filename, chunks.size(), imageUrls.size());
            return new ParseResult(buildMarkdownBlocks(chunks), imageUrls);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("[Word Parser] parse failed, file={}", filename, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Word 解析失败: " + e.getMessage());
        }
    }

    private List<String> extractAndUploadImages(XWPFDocument document, String filename) {
        List<String> urls = new ArrayList<>();
        String prefix = "knowledge/" + filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        List<XWPFPictureData> pictures = document.getAllPictures();

        for (int i = 0; i < pictures.size(); i++) {
            XWPFPictureData pic = pictures.get(i);
            byte[] data = pic.getData();
            if (data.length < MIN_IMAGE_SIZE) continue;

            try {
                String ext = pic.suggestFileExtension();
                String contentType = pic.getPackagePart().getContentType();
                String objectName = MinioService.generateObjectName(prefix, "_" + i + "." + ext);
                String url = minioService.uploadImage(data, objectName, contentType);
                urls.add(url);
            } catch (Exception e) {
                log.warn("[Word Parser] failed to upload image {}", i, e);
            }
        }
        return urls;
    }

    private List<ChunkResult> parseDocument(XWPFDocument document) {
        List<XWPFParagraph> paragraphs = document.getParagraphs();
        boolean hasHeadingStyle = paragraphs.stream()
                .anyMatch(this::isHeadingByStyle);

        if (hasHeadingStyle) {
            return parseByHeadingStyle(paragraphs);
        }
        return parseByRegex(paragraphs);
    }

    private List<ChunkResult> parseByHeadingStyle(List<XWPFParagraph> paragraphs) {
        List<ChunkResult> chunks = new ArrayList<>();
        StringBuilder currentBody = new StringBuilder();
        String currentTitle = null;
        String currentTag = null;

        for (XWPFParagraph paragraph : paragraphs) {
            String text = paragraph.getText().trim();
            if (text.isEmpty()) continue;

            if (isHeadingByStyle(paragraph)) {
                flushChunk(chunks, currentTitle, currentTag, currentBody);
                currentBody.setLength(0);
                currentTitle = text;
                currentTag = inferTag(text);
                continue;
            }

            currentBody.append(text).append("\n");

            if (currentBody.length() > MAX_CHUNK_LENGTH) {
                flushChunk(chunks, currentTitle, currentTag, currentBody);
                currentBody.setLength(0);
            }
        }

        flushChunk(chunks, currentTitle, currentTag, currentBody);
        return chunks;
    }

    private List<ChunkResult> parseByRegex(List<XWPFParagraph> paragraphs) {
        List<ChunkResult> chunks = new ArrayList<>();
        StringBuilder currentBody = new StringBuilder();
        String currentTitle = null;
        String currentTag = null;

        for (XWPFParagraph paragraph : paragraphs) {
            String text = paragraph.getText().trim();
            if (text.isEmpty()) continue;

            if (isTitleByRegex(text)) {
                flushChunk(chunks, currentTitle, currentTag, currentBody);
                currentBody.setLength(0);
                currentTitle = text;
                currentTag = inferTag(text);
                continue;
            }

            currentBody.append(text).append("\n");

            if (currentBody.length() > MAX_CHUNK_LENGTH) {
                flushChunk(chunks, currentTitle, currentTag, currentBody);
                currentBody.setLength(0);
            }
        }

        flushChunk(chunks, currentTitle, currentTag, currentBody);
        return chunks;
    }

    private boolean isHeadingByStyle(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) return false;
        return style.startsWith("Heading") || style.startsWith("heading")
                || style.matches("\\d");
    }

    private boolean isTitleByRegex(String line) {
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
                body.toString(),
                null
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
            if (chunk.getImageUrls() != null) {
                sb.append("image_urls: ").append(chunk.getImageUrls()).append("\n");
            }
            sb.append("source_type: docx\n");
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
        private String imageUrls;
    }
}
