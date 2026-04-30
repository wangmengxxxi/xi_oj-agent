package com.XI.xi_oj.ai.rag.parser;

import com.XI.xi_oj.ai.rag.RagImageSupport;
import com.XI.xi_oj.ai.rag.VisionModelHolder;
import com.XI.xi_oj.common.ErrorCode;
import com.XI.xi_oj.exception.BusinessException;
import com.XI.xi_oj.manager.MinioService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
public class PdfDocumentParser implements DocumentParser {

    private static final int MAX_CHUNK_LENGTH = 1000;
    private static final int MIN_CHUNK_LENGTH = 80;
    private static final int MAX_TITLE_LENGTH = 60;
    private static final int MIN_IMAGE_SIZE = 5000;
    private static final int MAX_IMAGE_DIMENSION = 1600;
    private static final int VL_IMAGE_DIMENSION = 512;
    private static final float IMAGE_VERTICAL_MARGIN = 120F;
    private static final int MAX_IMAGE_NEARBY_TEXT_LENGTH = 260;

    private static final Pattern FIGURE_CAPTION_PATTERN = Pattern.compile(
            "^图\\s*\\d+[‐‑‒–—―\\-]\\d+\\s+.+"
    );

    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "^("
                    + "\u7b2c[\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341\u767e\\d]+[\u7ae0\u8282\u7bc7]"
                    + "|\\d+\\.\\d+(\\.\\d+)?\\s+"
                    + "|[\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341]+[\u3001.]\\s*"
                    + "|#{1,3}\\s"
                    + ")(.+)$"
    );

    private static final Map<String, String> TAG_KEYWORDS = Map.ofEntries(
            Map.entry("\u5feb\u901f\u6392\u5e8f", "\u6392\u5e8f\u7b97\u6cd5"),
            Map.entry("\u5feb\u6392", "\u6392\u5e8f\u7b97\u6cd5"),
            Map.entry("\u6392\u5e8f", "\u6392\u5e8f\u7b97\u6cd5"),
            Map.entry("\u4e8c\u5206", "\u4e8c\u5206\u67e5\u627e"),
            Map.entry("\u52a8\u6001\u89c4\u5212", "\u52a8\u6001\u89c4\u5212"),
            Map.entry("dp", "\u52a8\u6001\u89c4\u5212"),
            Map.entry("\u8d2a\u5fc3", "\u8d2a\u5fc3\u7b97\u6cd5"),
            Map.entry("\u56de\u6eaf", "\u56de\u6eaf\u7b97\u6cd5"),
            Map.entry("\u9012\u5f52", "\u9012\u5f52"),
            Map.entry("\u94fe\u8868", "\u94fe\u8868"),
            Map.entry("\u4e8c\u53c9\u6811", "\u4e8c\u53c9\u6811"),
            Map.entry("\u6811", "\u6811"),
            Map.entry("\u56fe", "\u56fe\u8bba"),
            Map.entry("\u6808", "\u6808"),
            Map.entry("\u961f\u5217", "\u961f\u5217"),
            Map.entry("\u54c8\u5e0c", "\u54c8\u5e0c\u8868"),
            Map.entry("\u5806", "\u5806"),
            Map.entry("\u5b57\u7b26\u4e32", "\u5b57\u7b26\u4e32"),
            Map.entry("\u6570\u7ec4", "\u6570\u7ec4"),
            Map.entry("\u641c\u7d22", "\u641c\u7d22\u7b97\u6cd5"),
            Map.entry("\u5206\u6cbb", "\u5206\u6cbb\u7b97\u6cd5"),
            Map.entry("\u53cc\u6307\u9488", "\u53cc\u6307\u9488")
    );

    private final MinioService minioService;
    private final VisionModelHolder visionModelHolder;

    public PdfDocumentParser(MinioService minioService, VisionModelHolder visionModelHolder) {
        this.minioService = minioService;
        this.visionModelHolder = visionModelHolder;
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
        return parseWithImages(inputStream, filename, ImportProgressCallback.noop());
    }

    @Override
    public ParseResult parseWithImages(InputStream inputStream, String filename,
                                        ImportProgressCallback callback) {
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            String fullText = extractText(document);
            if (fullText == null || fullText.isBlank()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "PDF has no extractable text; scanned PDF is not supported yet.");
            }

            List<PageTextRange> pageRanges = extractPageRanges(document);
            Map<Integer, List<PositionedLine>> pageLines = extractPositionedLines(document);

            List<ChunkResult> chunks = smartChunk(fullText, pageRanges);
            if (chunks.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "No valid knowledge chunks were generated from PDF.");
            }
            callback.onStep("解析文档", 10);

            Map<String, byte[]> imageDataMap = new HashMap<>();
            Map<Integer, List<ImageRefCandidate>> pageImages = extractAndUploadImages(document, filename, pageLines, imageDataMap);
            callback.onStep("上传图片", 30);

            enrichCaptionsWithVL(pageImages, imageDataMap, callback);
            imageDataMap.clear();
            callback.onStep("生成图片描述", 70);

            assignImagesToChunks(chunks, pageImages, pageRanges, pageLines);

            List<String> allImageUrls = new ArrayList<>();
            pageImages.values().forEach(refs -> refs.forEach(ref -> allImageUrls.add(ref.url())));

            log.info("[PDF Parser] file={}, chunks={}, images={}", filename, chunks.size(), allImageUrls.size());
            return new ParseResult(buildMarkdownBlocks(chunks), allImageUrls);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("[PDF Parser] parse failed, file={}", filename, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "PDF parse failed: " + e.getMessage());
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

    private Map<Integer, List<PositionedLine>> extractPositionedLines(PDDocument document) throws IOException {
        Map<Integer, List<PositionedLine>> result = new HashMap<>();
        for (int pageIdx = 0; pageIdx < document.getNumberOfPages(); pageIdx++) {
            PositionedTextStripper stripper = new PositionedTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(pageIdx + 1);
            stripper.setEndPage(pageIdx + 1);
            stripper.getText(document);
            result.put(pageIdx, stripper.getLines());
        }
        return result;
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
            if (trimmed.isEmpty()) {
                continue;
            }

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
        if (line.length() > MAX_TITLE_LENGTH) {
            return false;
        }
        Matcher matcher = TITLE_PATTERN.matcher(line);
        return matcher.matches();
    }

    private void flushChunk(List<ChunkResult> chunks,
                            String title,
                            String tag,
                            StringBuilder body,
                            int startLine,
                            int endLine,
                            List<PageTextRange> pageRanges) {
        if (body.length() < MIN_CHUNK_LENGTH) {
            return;
        }
        int startPage = findPage(startLine, pageRanges);
        int endPage = findPage(endLine, pageRanges);
        chunks.add(new ChunkResult(
                title != null ? title : "\u672a\u5206\u7c7b\u77e5\u8bc6\u70b9",
                tag != null ? tag : "\u7b97\u6cd5\u57fa\u7840",
                body.toString(),
                startPage,
                endPage,
                startLine,
                endLine,
                null,
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

    private Map<Integer, List<ImageRefCandidate>> extractAndUploadImages(PDDocument document,
                                                                         String filename,
                                                                         Map<Integer, List<PositionedLine>> pageLines,
                                                                         Map<String, byte[]> imageDataMap) {
        Map<Integer, List<ImageRefCandidate>> pageImages = new HashMap<>();
        String prefix = "knowledge/" + filename.replaceAll("[^a-zA-Z0-9._-]", "_");

        for (int pageIdx = 0; pageIdx < document.getNumberOfPages(); pageIdx++) {
            PDPage page = document.getPage(pageIdx);
            PDResources resources = page.getResources();
            if (resources == null) {
                continue;
            }

            Map<COSName, List<PositionedImage>> positionsByName = groupPositionsByName(extractImagePositions(page));
            int imgIdx = 0;
            for (COSName name : resources.getXObjectNames()) {
                try {
                    PDXObject xObject = resources.getXObject(name);
                    if (!(xObject instanceof PDImageXObject image)) {
                        continue;
                    }

                    BufferedImage buffered = image.getImage();
                    if (buffered == null) {
                        continue;
                    }

                    BufferedImage forUpload = scaleIfNeeded(buffered);
                    byte[] pngBytes = toPngBytes(forUpload);

                    if (pngBytes.length < MIN_IMAGE_SIZE) {
                        if (forUpload != buffered) forUpload.flush();
                        buffered.flush();
                        continue;
                    }

                    String objectName = MinioService.generateObjectName(prefix, "_p" + pageIdx + "_" + imgIdx + ".png");
                    String url = minioService.uploadImage(pngBytes, objectName, "image/png");

                    BufferedImage vlScaled = scaleForVL(buffered);
                    byte[] vlBytes = toPngBytes(vlScaled);
                    if (vlScaled != buffered) vlScaled.flush();
                    if (forUpload != buffered) forUpload.flush();
                    buffered.flush();
                    imageDataMap.put(url, vlBytes);
                    Float centerY = firstCenterY(positionsByName.get(name));
                    String nearbyText = buildNearbyText(pageLines.get(pageIdx), centerY);
                    String caption = extractCaption(pageLines.get(pageIdx), centerY);
                    pageImages.computeIfAbsent(pageIdx, k -> new ArrayList<>())
                            .add(new ImageRefCandidate(url, pageIdx, imgIdx, centerY, nearbyText, caption));
                    imgIdx++;
                } catch (Exception e) {
                    log.warn("[PDF Parser] failed to extract image from page {}", pageIdx, e);
                }
            }
        }
        return pageImages;
    }

    private static final int VL_BATCH_SIZE = 20;

    private void enrichCaptionsWithVL(Map<Integer, List<ImageRefCandidate>> pageImages,
                                       Map<String, byte[]> imageDataMap,
                                       ImportProgressCallback callback) {
        if (!visionModelHolder.isAvailable()) {
            return;
        }
        List<Map.Entry<Integer, Integer>> captionlessIndices = new ArrayList<>();
        for (Map.Entry<Integer, List<ImageRefCandidate>> entry : pageImages.entrySet()) {
            List<ImageRefCandidate> candidates = entry.getValue();
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).caption() == null || candidates.get(i).caption().isBlank()) {
                    captionlessIndices.add(Map.entry(entry.getKey(), i));
                }
            }
        }
        if (captionlessIndices.isEmpty()) {
            return;
        }

        int total = captionlessIndices.size();
        AtomicInteger completed = new AtomicInteger(0);
        int concurrency = visionModelHolder.getConcurrency();
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);

        try {
            for (int batchStart = 0; batchStart < total; batchStart += VL_BATCH_SIZE) {
                int batchEnd = Math.min(batchStart + VL_BATCH_SIZE, total);
                List<Map.Entry<Integer, Integer>> batch = captionlessIndices.subList(batchStart, batchEnd);

                List<CompletableFuture<Void>> futures = batch.stream()
                        .map(idx -> CompletableFuture.runAsync(() -> {
                            int pageIdx = idx.getKey();
                            int imgIdx = idx.getValue();
                            ImageRefCandidate original = pageImages.get(pageIdx).get(imgIdx);
                            byte[] data = imageDataMap.get(original.url());
                            if (data != null) {
                                String caption = visionModelHolder.generateCaption(data, "image/png");
                                if (!caption.isBlank()) {
                                    pageImages.get(pageIdx).set(imgIdx, new ImageRefCandidate(
                                            original.url(), original.pageIndex(), original.imageIndex(),
                                            original.centerY(), original.nearbyText(), caption));
                                }
                            }
                            callback.onImageCaptionProgress(completed.incrementAndGet(), total);
                        }, executor))
                        .toList();

                try {
                    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                            .get(5, TimeUnit.MINUTES);
                } catch (Exception e) {
                    log.warn("[PDF Parser] VL batch timeout/error at batch {}-{}: {}",
                            batchStart, batchEnd, e.getMessage());
                }

                for (Map.Entry<Integer, Integer> idx : batch) {
                    imageDataMap.remove(pageImages.get(idx.getKey()).get(idx.getValue()).url());
                }
            }
            log.info("[PDF Parser] VL caption generated for {}/{} images", completed.get(), total);
        } finally {
            executor.shutdown();
        }
    }

    private Map<COSName, List<PositionedImage>> groupPositionsByName(List<PositionedImage> positionedImages) {
        Map<COSName, List<PositionedImage>> result = new HashMap<>();
        for (PositionedImage image : positionedImages) {
            result.computeIfAbsent(image.objectName(), key -> new ArrayList<>()).add(image);
        }
        return result;
    }

    private Float firstCenterY(List<PositionedImage> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        return images.get(0).centerY();
    }

    private byte[] toPngBytes(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        }
    }

    private BufferedImage scaleIfNeeded(BufferedImage original) {
        return scaleToMax(original, MAX_IMAGE_DIMENSION);
    }

    private BufferedImage scaleForVL(BufferedImage original) {
        return scaleToMax(original, VL_IMAGE_DIMENSION);
    }

    private BufferedImage scaleToMax(BufferedImage original, int maxDim) {
        int w = original.getWidth();
        int h = original.getHeight();
        if (w <= maxDim && h <= maxDim) {
            return original;
        }
        double scale = Math.min((double) maxDim / w, (double) maxDim / h);
        int newW = (int) (w * scale);
        int newH = (int) (h * scale);
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, newW, newH, null);
        g.dispose();
        return scaled;
    }

    private List<PositionedImage> extractImagePositions(PDPage page) {
        try {
            PositionedImageExtractor extractor = new PositionedImageExtractor(page);
            extractor.processPage(page);
            return extractor.images();
        } catch (Exception e) {
            log.debug("[PDF Parser] image position extraction failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void assignImagesToChunks(List<ChunkResult> chunks,
                                      Map<Integer, List<ImageRefCandidate>> pageImages,
                                      List<PageTextRange> pageRanges,
                                      Map<Integer, List<PositionedLine>> pageLines) {
        for (ChunkResult chunk : chunks) {
            List<RagImageSupport.ImageRef> refs = new ArrayList<>();
            for (int p = chunk.getStartPage(); p <= chunk.getEndPage(); p++) {
                List<ImageRefCandidate> candidates = pageImages.get(p);
                if (candidates == null || candidates.isEmpty()) {
                    continue;
                }
                for (ImageRefCandidate candidate : candidates) {
                    if (belongsToChunk(candidate, chunk, p, pageRanges, pageLines)) {
                        refs.add(new RagImageSupport.ImageRef(
                                candidate.url(),
                                chunk.getTitle(),
                                chunk.getTag(),
                                candidate.nearbyText(),
                                candidate.caption(),
                                candidate.pageIndex() + 1
                        ));
                    }
                }
            }
            if (!refs.isEmpty()) {
                chunk.setImageUrls(refs.stream()
                        .map(RagImageSupport.ImageRef::url)
                        .collect(Collectors.joining(",")));
                chunk.setImageRefs(RagImageSupport.buildImageRefsJson(refs));
            }
        }
    }

    private boolean belongsToChunk(ImageRefCandidate image,
                                   ChunkResult chunk,
                                   int pageIndex,
                                   List<PageTextRange> pageRanges,
                                   Map<Integer, List<PositionedLine>> pageLines) {
        if (image.centerY() != null && isInChunkVerticalRange(image.centerY(), chunk, pageIndex, pageRanges, pageLines)) {
            return true;
        }
        String chunkText = chunk.getTitle() + "\n" + chunk.getTag() + "\n" + chunk.getBody();
        return RagImageSupport.hasMeaningfulOverlap(chunkText, image.nearbyText());
    }

    private boolean isInChunkVerticalRange(Float imageCenterY,
                                           ChunkResult chunk,
                                           int pageIndex,
                                           List<PageTextRange> pageRanges,
                                           Map<Integer, List<PositionedLine>> pageLines) {
        List<PositionedLine> lines = pageLines.get(pageIndex);
        PageTextRange range = pageRanges.stream()
                .filter(r -> r.pageIndex() == pageIndex)
                .findFirst()
                .orElse(null);
        if (lines == null || lines.isEmpty() || range == null) {
            return false;
        }
        int localStart = Math.max(0, chunk.getStartLine() - range.startLine());
        int localEnd = Math.min(lines.size() - 1, chunk.getEndLine() - range.startLine());
        if (localStart > localEnd) {
            return false;
        }
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = localStart; i <= localEnd; i++) {
            float y = lines.get(i).y();
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        return imageCenterY >= minY - IMAGE_VERTICAL_MARGIN
                && imageCenterY <= maxY + IMAGE_VERTICAL_MARGIN;
    }

    private String buildNearbyText(List<PositionedLine> lines, Float centerY) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        List<PositionedLine> selected;
        if (centerY == null) {
            selected = lines.subList(0, Math.min(lines.size(), 6));
        } else {
            selected = lines.stream()
                    .filter(line -> Math.abs(line.y() - centerY) <= IMAGE_VERTICAL_MARGIN)
                    .limit(8)
                    .toList();
            if (selected.isEmpty()) {
                selected = lines.stream()
                        .sorted(Comparator.comparingDouble(line -> Math.abs(line.y() - centerY)))
                        .limit(6)
                        .toList();
            }
        }
        String text = selected.stream()
                .map(PositionedLine::text)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" "));
        return text.length() <= MAX_IMAGE_NEARBY_TEXT_LENGTH
                ? text
                : text.substring(0, MAX_IMAGE_NEARBY_TEXT_LENGTH);
    }

    private String extractCaption(List<PositionedLine> lines, Float centerY) {
        if (lines == null || lines.isEmpty() || centerY == null) {
            return "";
        }
        return lines.stream()
                .filter(line -> Math.abs(line.y() - centerY) <= IMAGE_VERTICAL_MARGIN * 1.5F)
                .map(PositionedLine::text)
                .filter(s -> s != null && FIGURE_CAPTION_PATTERN.matcher(s.trim()).matches())
                .findFirst()
                .map(String::trim)
                .orElse("");
    }

    private String inferTag(String title) {
        String lower = title.toLowerCase();
        for (Map.Entry<String, String> entry : TAG_KEYWORDS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "\u7b97\u6cd5\u57fa\u7840";
    }

    private String buildMarkdownBlocks(List<ChunkResult> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            ChunkResult chunk = chunks.get(i);
            if (i > 0) {
                sb.append("\n---\n");
            }
            sb.append("content_type: \u77e5\u8bc6\u70b9\n");
            sb.append("tag: ").append(chunk.getTag()).append("\n");
            sb.append("title: ").append(chunk.getTitle()).append("\n");
            if (chunk.getImageUrls() != null) {
                sb.append("image_urls: ").append(chunk.getImageUrls()).append("\n");
            }
            if (chunk.getImageRefs() != null) {
                sb.append("image_refs: ").append(chunk.getImageRefs()).append("\n");
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
        private int startLine;
        private int endLine;
        private String imageUrls;
        private String imageRefs;
    }

    private static class PositionedTextStripper extends PDFTextStripper {
        private final List<PositionedLine> lines = new ArrayList<>();

        private PositionedTextStripper() throws IOException {
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            String trimmed = text == null ? "" : text.trim();
            if (trimmed.isBlank() || textPositions == null || textPositions.isEmpty()) {
                return;
            }
            float y = 0F;
            for (TextPosition position : textPositions) {
                y += position.getYDirAdj();
            }
            lines.add(new PositionedLine(trimmed, y / textPositions.size()));
        }

        private List<PositionedLine> getLines() {
            return lines;
        }
    }

    private static class PositionedImageExtractor extends PDFStreamEngine {
        private final PDPage page;
        private final List<PositionedImage> images = new ArrayList<>();

        private PositionedImageExtractor(PDPage page) throws IOException {
            this.page = page;
            addOperator(new DrawObject(this));
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if ("Do".equals(operator.getName()) && operands != null && !operands.isEmpty()
                    && operands.get(0) instanceof COSName objectName) {
                PDXObject xObject = getResources().getXObject(objectName);
                if (xObject instanceof PDImage) {
                    Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
                    float y = ctm.getTranslateY();
                    float h = Math.abs(ctm.getScalingFactorY());
                    float pageHeight = page.getMediaBox().getHeight();
                    images.add(new PositionedImage(objectName, pageHeight - y - h / 2F));
                }
            }
            super.processOperator(operator, operands);
        }

        private List<PositionedImage> images() {
            return images;
        }
    }

    private record PageTextRange(int pageIndex, int startLine, int endLine) {
    }

    private record PositionedLine(String text, float y) {
    }

    private record PositionedImage(COSName objectName, float centerY) {
    }

    private record ImageRefCandidate(String url,
                                     int pageIndex,
                                     int imageIndex,
                                     Float centerY,
                                     String nearbyText,
                                     String caption) {
    }
}
