package com.XI.xi_oj.ai.rag.parser;

import com.XI.xi_oj.ai.rag.RagImageSupport;
import com.XI.xi_oj.ai.rag.VisionModelHolder;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Slf4j
public class WordDocumentParser implements DocumentParser {

    private static final int MAX_CHUNK_LENGTH = 1000;
    private static final int MIN_CHUNK_LENGTH = 80;
    private static final int MAX_TITLE_LENGTH = 60;
    private static final int MIN_IMAGE_SIZE = 5000;
    private static final int VL_IMAGE_DIMENSION = 512;

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
    private final VisionModelHolder visionModelHolder;

    public WordDocumentParser(MinioService minioService, VisionModelHolder visionModelHolder) {
        this.minioService = minioService;
        this.visionModelHolder = visionModelHolder;
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
        return parseWithImages(inputStream, filename, ImportProgressCallback.noop());
    }

    @Override
    public ParseResult parseWithImages(InputStream inputStream, String filename,
                                        ImportProgressCallback callback) {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            List<ChunkResult> chunks = parseDocument(document);
            if (chunks.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "Word 文档解析后未生成有效知识块");
            }
            callback.onStep("解析文档", 10);

            List<ImageWithCaption> images = extractUploadAndCaption(document, filename, callback);
            callback.onStep("生成图片描述", 70);

            assignImagesToChunks(chunks, images);

            List<String> imageUrls = images.stream().map(ImageWithCaption::url).toList();
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

    private List<ImageWithCaption> extractUploadAndCaption(XWPFDocument document, String filename,
                                                            ImportProgressCallback callback) {
        List<ImageWithCaption> result = new ArrayList<>();
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
                byte[] vlData = scaleForVL(data, contentType);
                result.add(new ImageWithCaption(url, "", vlData, contentType));
            } catch (Exception e) {
                log.warn("[Word Parser] failed to upload image {}", i, e);
            }
        }
        callback.onStep("上传图片", 30);

        if (!result.isEmpty() && visionModelHolder.isAvailable()) {
            int total = result.size();
            AtomicInteger completed = new AtomicInteger(0);
            int concurrency = visionModelHolder.getConcurrency();
            ExecutorService executor = Executors.newFixedThreadPool(concurrency);
            try {
                List<CompletableFuture<String>> futures = result.stream()
                        .map(img -> CompletableFuture.supplyAsync(() -> {
                            String caption = visionModelHolder.generateCaption(img.data(), img.mimeType());
                            callback.onImageCaptionProgress(completed.incrementAndGet(), total);
                            return caption;
                        }, executor))
                        .toList();
                try {
                    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                            .get(10, TimeUnit.MINUTES);
                } catch (Exception e) {
                    log.warn("[Word Parser] VL caption timeout/error: {}", e.getMessage());
                }
                for (int i = 0; i < result.size(); i++) {
                    try {
                        String caption = futures.get(i).getNow("");
                        if (caption != null && !caption.isBlank()) {
                            ImageWithCaption old = result.get(i);
                            result.set(i, new ImageWithCaption(old.url(), caption, null, null));
                        }
                    } catch (Exception ignored) {}
                }
                log.info("[Word Parser] VL caption generated for {} images", completed.get());
            } finally {
                executor.shutdown();
            }
        }
        // 释放图片字节引用
        for (int i = 0; i < result.size(); i++) {
            ImageWithCaption img = result.get(i);
            if (img.data() != null) {
                result.set(i, new ImageWithCaption(img.url(), img.caption(), null, null));
            }
        }
        return result;
    }

    private void assignImagesToChunks(List<ChunkResult> chunks, List<ImageWithCaption> images) {
        if (images.isEmpty() || chunks.isEmpty()) return;
        for (ImageWithCaption img : images) {
            ChunkResult best = chunks.get(0);
            if (img.caption() != null && !img.caption().isBlank()) {
                for (ChunkResult chunk : chunks) {
                    String chunkText = chunk.getTitle() + "\n" + chunk.getBody();
                    if (RagImageSupport.hasMeaningfulOverlap(chunkText, img.caption())) {
                        best = chunk;
                        break;
                    }
                }
            }
            best.getImageUrlList().add(img.url());
            best.getImageRefList().add(new RagImageSupport.ImageRef(
                    img.url(), best.getTitle(), best.getTag(), "", img.caption(), null));
        }
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
                new ArrayList<>(),
                new ArrayList<>()
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
            if (!chunk.getImageUrlList().isEmpty()) {
                sb.append("image_urls: ").append(String.join(",", chunk.getImageUrlList())).append("\n");
                sb.append("image_refs: ").append(RagImageSupport.buildImageRefsJson(chunk.getImageRefList())).append("\n");
            }
            sb.append("source_type: docx\n");
            sb.append("\n");
            sb.append(chunk.getBody().trim());
        }
        return sb.toString();
    }

    private byte[] scaleForVL(byte[] imageData, String mimeType) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageData));
            if (original == null) return imageData;
            int w = original.getWidth();
            int h = original.getHeight();
            if (w <= VL_IMAGE_DIMENSION && h <= VL_IMAGE_DIMENSION) {
                original.flush();
                return imageData;
            }
            double scale = Math.min((double) VL_IMAGE_DIMENSION / w, (double) VL_IMAGE_DIMENSION / h);
            int newW = (int) (w * scale);
            int newH = (int) (h * scale);
            BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, newW, newH, null);
            g.dispose();
            original.flush();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            String format = mimeType != null && mimeType.contains("jpeg") ? "jpeg" : "png";
            ImageIO.write(scaled, format, baos);
            scaled.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            return imageData;
        }
    }

    @Data
    @AllArgsConstructor
    private static class ChunkResult {
        private String title;
        private String tag;
        private String body;
        private List<String> imageUrlList;
        private List<RagImageSupport.ImageRef> imageRefList;
    }

    private record ImageWithCaption(String url, String caption, byte[] data, String mimeType) {}
}
