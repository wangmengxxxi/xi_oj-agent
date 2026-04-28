package com.XI.xi_oj.ai.rag.parser;

import com.XI.xi_oj.common.ErrorCode;
import com.XI.xi_oj.exception.BusinessException;
import com.XI.xi_oj.manager.MinioService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

@Component
@Slf4j
public class PdfDocumentParser implements DocumentParser {

    private static final int MAX_CHUNK_LENGTH = 1000;
    private static final int MIN_CHUNK_LENGTH = 80;
    private static final int MAX_TITLE_LENGTH = 60;
    private static final int MIN_IMAGE_SIZE = 5000;
    private static final int MAX_IMAGE_DIMENSION = 1600;

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

    public PdfDocumentParser(MinioService minioService) {
        this.minioService = minioService;
    }

    @Override
    public boolean supports(String extension) {
        return "pdf".equalsIgnoreCase(extension);
    }

    @Override
    public String parse(InputStream inputStream, String filename) {
        return parseWithImages(inputStream, filename).markdownBlocks();
    }

    @Override
    public ParseResult parseWithImages(InputStream inputStream, String filename) {
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            int totalPages = document.getNumberOfPages();

            String fullText = extractText(document);
            if (fullText == null || fullText.isBlank()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "该 PDF 为扫描版或无文本内容，暂不支持导入");
            }

            // 按页提取文本，记录每页的行范围
            List<PageTextRange> pageRanges = extractPageRanges(document);

            List<ChunkResult> chunks = smartChunk(fullText, pageRanges);
            if (chunks.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "PDF 解析后未生成有效知识块");
            }

            // 提取图片并上传 MinIO，按页码关联
            Map<Integer, List<String>> pageImageUrls = extractAndUploadImages(document, filename);

            // 将图片 URL 关联到对应 chunk
            assignImagesToChunks(chunks, pageImageUrls);

            List<String> allImageUrls = new ArrayList<>();
            pageImageUrls.values().forEach(allImageUrls::addAll);

            log.info("[PDF Parser] file={}, chunks={}, images={}", filename, chunks.size(), allImageUrls.size());
            return new ParseResult(buildMarkdownBlocks(chunks), allImageUrls);
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

    private List<PageTextRange> extractPageRanges(PDDocument document) throws IOException {
        List<PageTextRange> ranges = new ArrayList<>();
        int totalPages = document.getNumberOfPages();
        int globalLineOffset = 0;
        PDFTextStripper stripper = new PDFTextStripper();
        for (int pageIdx = 0; pageIdx < totalPages; pageIdx++) {
            stripper.setStartPage(pageIdx + 1);
            stripper.setEndPage(pageIdx + 1);
            String pageText = stripper.getText(document);
            int lineCount = pageText.split("\\R", -1).length;
            ranges.add(new PageTextRange(pageIdx, globalLineOffset, globalLineOffset + lineCount));
            globalLineOffset += lineCount;
        }
        return ranges;
    }

    private List<ChunkResult> smartChunk(String fullText, List<PageTextRange> pageRanges) {
        String[] lines = fullText.split("\\R");
        List<ChunkResult> chunks = new ArrayList<>();
        StringBuilder currentBody = new StringBuilder();
        String currentTitle = null;
        String currentTag = null;
        int chunkStartLine = 0;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) continue;

            if (isTitle(trimmed)) {
                flushChunk(chunks, currentTitle, currentTag, currentBody, chunkStartLine, i, pageRanges);
                currentBody.setLength(0);
                currentTitle = trimmed;
                currentTag = inferTag(trimmed);
                chunkStartLine = i;
                continue;
            }

            currentBody.append(trimmed).append("\n");

            if (currentBody.length() > MAX_CHUNK_LENGTH) {
                flushChunk(chunks, currentTitle, currentTag, currentBody, chunkStartLine, i, pageRanges);
                currentBody.setLength(0);
                chunkStartLine = i + 1;
            }
        }

        flushChunk(chunks, currentTitle, currentTag, currentBody, chunkStartLine, lines.length - 1, pageRanges);
        return chunks;
    }

    private boolean isTitle(String line) {
        if (line.length() > MAX_TITLE_LENGTH) return false;
        Matcher matcher = TITLE_PATTERN.matcher(line);
        return matcher.matches();
    }

    private void flushChunk(List<ChunkResult> chunks, String title,
                            String tag, StringBuilder body,
                            int startLine, int endLine,
                            List<PageTextRange> pageRanges) {
        if (body.length() < MIN_CHUNK_LENGTH) return;
        int startPage = findPage(startLine, pageRanges);
        int endPage = findPage(endLine, pageRanges);
        chunks.add(new ChunkResult(
                title != null ? title : "未分类知识点",
                tag != null ? tag : "算法基础",
                body.toString(),
                startPage,
                endPage,
                null
        ));
    }

    private int findPage(int lineIndex, List<PageTextRange> pageRanges) {
        for (PageTextRange range : pageRanges) {
            if (lineIndex >= range.startLine && lineIndex < range.endLine) {
                return range.pageIndex;
            }
        }
        return pageRanges.isEmpty() ? 0 : pageRanges.get(pageRanges.size() - 1).pageIndex;
    }

    private Map<Integer, List<String>> extractAndUploadImages(PDDocument document, String filename) {
        Map<Integer, List<String>> pageImageUrls = new HashMap<>();
        String prefix = "knowledge/" + filename.replaceAll("[^a-zA-Z0-9._-]", "_");

        for (int pageIdx = 0; pageIdx < document.getNumberOfPages(); pageIdx++) {
            PDPage page = document.getPage(pageIdx);
            PDResources resources = page.getResources();
            if (resources == null) continue;

            int imgIdx = 0;
            for (COSName name : resources.getXObjectNames()) {
                try {
                    PDXObject xObject = resources.getXObject(name);
                    if (!(xObject instanceof PDImageXObject image)) continue;

                    BufferedImage buffered = image.getImage();
                    if (buffered == null) continue;

                    buffered = scaleIfNeeded(buffered);
                    byte[] pngBytes = toPngBytes(buffered);
                    buffered.flush();

                    if (pngBytes.length < MIN_IMAGE_SIZE) continue;

                    String objectName = MinioService.generateObjectName(
                            prefix, "_p" + pageIdx + "_" + imgIdx + ".png");
                    String url = minioService.uploadImage(pngBytes, objectName, "image/png");
                    pageImageUrls.computeIfAbsent(pageIdx, k -> new ArrayList<>()).add(url);
                    imgIdx++;
                } catch (Exception e) {
                    log.warn("[PDF Parser] failed to extract image from page {}", pageIdx, e);
                }
            }
        }
        return pageImageUrls;
    }

    private byte[] toPngBytes(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        }
    }

    private BufferedImage scaleIfNeeded(BufferedImage original) {
        int w = original.getWidth();
        int h = original.getHeight();
        if (w <= MAX_IMAGE_DIMENSION && h <= MAX_IMAGE_DIMENSION) {
            return original;
        }
        double scale = Math.min((double) MAX_IMAGE_DIMENSION / w, (double) MAX_IMAGE_DIMENSION / h);
        int newW = (int) (w * scale);
        int newH = (int) (h * scale);
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, newW, newH, null);
        g.dispose();
        original.flush();
        return scaled;
    }

    private void assignImagesToChunks(List<ChunkResult> chunks, Map<Integer, List<String>> pageImageUrls) {
        for (ChunkResult chunk : chunks) {
            List<String> urls = new ArrayList<>();
            for (int p = chunk.getStartPage(); p <= chunk.getEndPage(); p++) {
                List<String> pageUrls = pageImageUrls.get(p);
                if (pageUrls != null) urls.addAll(pageUrls);
            }
            if (!urls.isEmpty()) {
                chunk.setImageUrls(String.join(",", urls));
            }
        }
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
            sb.append("source_type: pdf\n");
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
        private int startPage;
        private int endPage;
        private String imageUrls;
    }

    private record PageTextRange(int pageIndex, int startLine, int endLine) {}
}
