# XI OJ 知识库多格式导入扩展方案：PDF / Word 解析 + 图片提取 + 自动分片

更新时间：2026-04-28
前置依赖：已完成 RAG 知识库基础模块（参见 `aigc_engineering_implementation_guide.md` 第 6 章）

> **当前落地状态（2026-04-30）**
> 1. 本方案所有核心功能已在仓库中落地实现，包括 PDF 解析器（`PdfDocumentParser`）、Word 解析器（`WordDocumentParser`）、MinIO 图片存储（`MinioService`）、Controller 多格式路由 + PDF/DOCX 统一异步处理（`KnowledgeImportController` + `KnowledgeImportAsyncService`）；
> 2. `DocumentParser` 接口已扩展 `parseWithImages()` 方法（含三参数版本支持 `ImportProgressCallback` 进度回调），返回 `ParseResult(markdownBlocks, imageUrls)` record，支持图片提取链路；
> 3. `KnowledgeInitializer.parseBlock()` 已适配 `image_urls`（逗号分隔多图）和 `source_type` 新 metadata 字段，`buildSearchableText()` 会将 `has_images: true` 和 `image_urls` 写入可检索文本；
> 4. RAG 检索侧已通过 `ImageAwareContentRetriever` 装饰器实现图片感知：检索到含 `image_urls` 的 chunk 时，自动在上下文中追加 `[RAG_SOURCE_IMAGES]` 段和 markdown 图片引用，LLM 可直接在回答中保留图片链接；
> 5. System Prompt 中已增加【图片引用规范】，要求 LLM 原样保留 RAG 检索到的图片链接，禁止修改或编造图片 URL；
> 6. VL 视觉模型（Qwen-VL）已集成：导入时自动为每张图片生成一句话描述（caption），写入 `image_refs` metadata，用于图片-chunk 语义匹配和检索时 LLM 理解图片内容；
> 7. 前端导入流程已支持进度条实时展示（解析文档 → 上传图片 → 生成图片描述 → 写入向量库）。

---

## 〇、背景与目标

当前知识库导入仅支持 `.md` 格式，管理员需要手动将内容整理为 `content_type / tag / title + body` 的 block 格式。这在小规模知识条目时可行，但面对大型文档（如 Hello 算法 PDF、算法教材 Word 文档）时效率极低。

本方案的目标：

1. 新增 PDF（`.pdf`）和 Word（`.docx`）两种文件格式的导入支持。
2. 实现自动解析：从文档中提取文本、识别章节结构、自动切片为符合现有 block 格式的知识条目。
3. 实现图片提取：从 PDF/Word 中提取内嵌图片，上传至 MinIO，图片 URL 关联到对应知识块的 metadata。
4. 完全复用现有的 `KnowledgeInitializer.parseAndStore()` 链路，解析结果转为 markdown block 格式后走统一的 embedding → Milvus 流程。

---

## 一、整体架构与数据流

### 1.1 当前链路（仅 Markdown）

```
管理员上传 .md 文件
  → KnowledgeImportController 接收
  → KnowledgeInitializer.parseAndStore() 按 "---" 分块
  → 提取 metadata (content_type / tag / title) + body
  → AiModelHolder.getEmbeddingModel().embed()
  → MilvusEmbeddingStore.addAll()
  → 清除 RAG 缓存
```

### 1.2 扩展后链路（Markdown + PDF + Word）

```
管理员上传文件（.md / .pdf / .docx）
  → KnowledgeImportController 根据后缀分发
  │
  ├─ .md → 现有逻辑不变
  │
  ├─ .pdf → PdfDocumentParser 解析
  │    ├─ PDFBox 提取全文文本（按页）
  │    ├─ 识别章节标题（正则匹配标题模式）
  │    ├─ 按章节 + 长度阈值自动切片
  │    ├─ PDFBox 提取内嵌图片 → MinioService 上传 → 获取 URL
  │    └─ 组装为 markdown block 格式字符串
  │
  ├─ .docx → WordDocumentParser 解析
  │    ├─ Apache POI 提取段落文本
  │    ├─ 识别标题样式（Heading1 / Heading2 等）
  │    ├─ 按标题层级自动切片
  │    ├─ 提取内嵌图片 → MinioService 上传 → 获取 URL
  │    └─ 组装为 markdown block 格式字符串
  │
  → 统一输出 markdown block 格式字符串
  → KnowledgeInitializer.parseAndStore()（复用现有逻辑）
  → embedding → Milvus → 清除 RAG 缓存
```

### 1.3 架构设计原则

1. 解析层与存储层解耦：`DocumentParser` 只负责"文件 → markdown blocks 字符串"的转换，不直接操作 Milvus。
2. 统一出口：所有格式最终都转为现有的 markdown block 格式，复用 `parseAndStore()` 的 metadata 提取 + embedding 逻辑。
3. 图片独立存储：图片上传 MinIO 后，URL 作为 metadata 字段附加到对应 chunk，不影响文本 embedding 质量。

---

## 二、依赖选型与引入

### 2.1 新增 Maven 依赖

```xml
<!-- PDF 解析：Apache PDFBox -->
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>3.0.4</version>
</dependency>

<!-- Word 解析：Apache POI -->
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.3.0</version>
</dependency>

<!-- 对象存储：MinIO SDK -->
<dependency>
    <groupId>io.minio</groupId>
    <artifactId>minio</artifactId>
    <version>8.5.14</version>
</dependency>
```

### 2.2 选型理由

| 组件 | 选型 | 理由 |
|---|---|---|
| PDF 解析 | Apache PDFBox 3.x | 纯 Java、无需本地依赖、支持文本提取 + 图片提取 + 按页渲染，社区活跃 |
| Word 解析 | Apache POI 5.x | Java 生态 Word 解析事实标准，支持 .docx 段落/标题/图片提取 |
| 对象存储 | MinIO | 兼容 S3 协议、可本地部署、轻量，适合开发和生产环境 |

不选 Apache Tika 的原因：Tika 是"万能解析器"，依赖重（引入后 jar 包增大 50MB+），而我们只需要 PDF + Word 两种格式，PDFBox + POI 更轻量可控。

---

## 三、MinIO 配置与 Service

### 3.1 application.yml 新增配置

```yaml
minio:
  endpoint: http://localhost:9000
  access-key: minioadmin
  secret-key: minioadmin
  bucket: oj-knowledge
```

### 3.2 MinioConfig 配置类

```java
@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
```

### 3.3 MinioService

```java
@Service
@Slf4j
public class MinioService {

    @Resource
    private MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    @Value("${minio.endpoint}")
    private String endpoint;

    @PostConstruct
    public void init() {
        try {
            boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(
                    MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[MinIO] created bucket: {}", bucket);
            }
        } catch (Exception e) {
            log.error("[MinIO] init bucket failed", e);
        }
    }

    public String uploadImage(byte[] data, String objectName,
                              String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(new ByteArrayInputStream(data),
                            data.length, -1)
                    .contentType(contentType)
                    .build());
            return endpoint + "/" + bucket + "/" + objectName;
        } catch (Exception e) {
            log.error("[MinIO] upload failed, object={}", objectName, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "图片上传失败");
        }
    }
}
```

---

## 四、文档解析器设计

### 4.1 统一接口

```java
public interface DocumentParser {

    /** 解析文档，输出 markdown block 格式字符串（可直接传入 KnowledgeInitializer.parseAndStore） */
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
```

### 4.2 核心设计决策

**为什么输出是 markdown block 字符串而不是 List\<TextSegment\>？**

因为现有的 `KnowledgeInitializer.parseAndStore()` 接收的就是 markdown 字符串，内部完成分块 → metadata 提取 → embedding → 存储的全流程。让解析器输出相同格式的字符串，可以零修改复用这条链路。

**chunk 切片策略：**

| 策略 | 适用场景 | 实现方式 |
|---|---|---|
| 按章节标题切 | 结构化文档（Hello 算法 PDF、教材） | 正则匹配标题模式，每个标题开始新 chunk |
| 按段落 + 长度阈值切 | 无明显标题的连续文本 | 累积段落直到超过阈值（500 字符），开始新 chunk |
| 按页切 | 兜底策略 | 每页作为一个 chunk，适用于无法识别结构的 PDF |

优先级：标题切 > 段落切 > 按页切。三种策略在代码中按优先级 fallback。

---

## 五、PDF 解析器实现

### 5.1 PdfDocumentParser 核心逻辑

```java
@Component
@Slf4j
public class PdfDocumentParser implements DocumentParser {

    @Resource
    private MinioService minioService;

    private static final int MAX_CHUNK_LENGTH = 550;
    private static final int MIN_CHUNK_LENGTH = 80;

    private static final Pattern TITLE_PATTERN = Pattern.compile(
        "^(第[一二三四五六七八九十百千\\d]+[章节篇]|" +
        "\\d+\\.\\d+|\\d+\\.|" +
        "[一二三四五六七八九十]+[、.]|" +
        "#{1,3}\\s)" +
        "\\s*(.+)$"
    );

    @Override
    public boolean supports(String extension) {
        return "pdf".equalsIgnoreCase(extension);
    }

    @Override
    public String parse(InputStream inputStream, String filename) {
        try (PDDocument document = Loader.loadPDF(
                inputStream.readAllBytes())) {
            List<String> imageUrls = extractAndUploadImages(
                    document, filename);
            String fullText = extractText(document);
            List<ChunkResult> chunks = smartChunk(
                    fullText, imageUrls);
            return buildMarkdownBlocks(chunks);
        } catch (IOException e) {
            log.error("[PDF Parser] parse failed, file={}",
                      filename, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "PDF 解析失败: " + e.getMessage());
        }
    }

    private String extractText(PDDocument document)
            throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        return stripper.getText(document);
    }
}
```

### 5.2 智能切片算法 smartChunk

```java
private List<ChunkResult> smartChunk(String fullText,
                                      List<String> imageUrls) {
    String[] lines = fullText.split("\\R");
    List<ChunkResult> chunks = new ArrayList<>();
    StringBuilder currentBody = new StringBuilder();
    String currentTitle = null;
    String currentTag = null;
    int imageIndex = 0;

    for (String line : lines) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) continue;

        Matcher matcher = TITLE_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            if (currentTitle != null
                    && currentBody.length() >= MIN_CHUNK_LENGTH) {
                String imgUrl = imageIndex < imageUrls.size()
                        ? imageUrls.get(imageIndex++) : null;
                chunks.add(new ChunkResult(currentTitle,
                        currentTag, currentBody.toString(),
                        imgUrl));
                currentBody.setLength(0);
            }
            currentTitle = trimmed;
            currentTag = inferTag(trimmed);
            continue;
        }

        currentBody.append(trimmed).append("\n");

        if (currentBody.length() > MAX_CHUNK_LENGTH) {
            String imgUrl = imageIndex < imageUrls.size()
                    ? imageUrls.get(imageIndex++) : null;
            chunks.add(new ChunkResult(
                    currentTitle != null
                            ? currentTitle : "未分类知识点",
                    currentTag != null ? currentTag : "通用",
                    currentBody.toString(), imgUrl));
            currentBody.setLength(0);
        }
    }

    if (currentBody.length() >= MIN_CHUNK_LENGTH) {
        chunks.add(new ChunkResult(
                currentTitle != null
                        ? currentTitle : "未分类知识点",
                currentTag != null ? currentTag : "通用",
                currentBody.toString(),
                imageIndex < imageUrls.size()
                        ? imageUrls.get(imageIndex) : null));
    }

    return chunks;
}
```

### 5.3 标签自动推断 inferTag

```java
private static final Map<String, String> TAG_KEYWORDS =
        Map.ofEntries(
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

private String inferTag(String title) {
    String lower = title.toLowerCase();
    for (Map.Entry<String, String> entry
            : TAG_KEYWORDS.entrySet()) {
        if (lower.contains(entry.getKey())) {
            return entry.getValue();
        }
    }
    return "算法基础";
}
```

### 5.4 图片提取与上传

```java
private List<String> extractAndUploadImages(
        PDDocument document, String filename) {
    List<String> urls = new ArrayList<>();
    String baseName = filename.replaceAll("\\.[^.]+$", "");

    for (int i = 0; i < document.getNumberOfPages(); i++) {
        PDPage page = document.getPage(i);
        PDResources resources = page.getResources();
        if (resources == null) continue;

        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject xObject = resources.getXObject(name);
                if (xObject instanceof PDImageXObject image) {
                    BufferedImage bufferedImage =
                            image.getImage();
                    ByteArrayOutputStream baos =
                            new ByteArrayOutputStream();
                    ImageIO.write(bufferedImage, "png", baos);
                    byte[] imageBytes = baos.toByteArray();

                    String objectName = String.format(
                            "knowledge/%s/page%d_%s.png",
                            baseName, i + 1, name.getName());
                    String url = minioService.uploadImage(
                            imageBytes, objectName,
                            "image/png");
                    urls.add(url);
                }
            } catch (Exception e) {
                log.warn("[PDF Parser] extract image failed,"
                         + " page={}, name={}",
                         i + 1, name, e);
            }
        }
    }

    log.info("[PDF Parser] extracted {} images from {}",
             urls.size(), filename);
    return urls;
}
```

### 5.5 组装 Markdown Block 格式

```java
@Data
@AllArgsConstructor
private static class ChunkResult {
    private String title;
    private String tag;
    private String body;
    private String imageUrl;
}

private String buildMarkdownBlocks(List<ChunkResult> chunks) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < chunks.size(); i++) {
        ChunkResult chunk = chunks.get(i);
        if (i > 0) sb.append("\n---\n");
        sb.append("content_type: 知识点\n");
        sb.append("tag: ").append(chunk.getTag()).append("\n");
        sb.append("title: ").append(chunk.getTitle())
          .append("\n");
        if (chunk.getImageUrl() != null) {
            sb.append("image_url: ")
              .append(chunk.getImageUrl()).append("\n");
        }
        sb.append("\n");
        sb.append(chunk.getBody().trim());
    }
    return sb.toString();
}
```

---

## 六、Word 解析器实现

### 6.1 WordDocumentParser 核心逻辑

```java
@Component
@Slf4j
public class WordDocumentParser implements DocumentParser {

    @Resource
    private MinioService minioService;

    private static final int MAX_CHUNK_LENGTH = 550;
    private static final int MIN_CHUNK_LENGTH = 80;

    @Override
    public boolean supports(String extension) {
        return "docx".equalsIgnoreCase(extension);
    }

    @Override
    public String parse(InputStream inputStream, String filename) {
        try (XWPFDocument document =
                new XWPFDocument(inputStream)) {
            List<String> imageUrls = extractAndUploadImages(
                    document, filename);
            List<ChunkResult> chunks = parseByHeadings(
                    document, imageUrls);
            return buildMarkdownBlocks(chunks);
        } catch (IOException e) {
            log.error("[Word Parser] parse failed, file={}",
                      filename, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Word 解析失败: " + e.getMessage());
        }
    }
}
```

### 6.2 按标题样式切片

Word 文档的优势在于标题有明确的样式标记（Heading1、Heading2 等），比 PDF 的正则匹配更可靠。

```java
private List<ChunkResult> parseByHeadings(
        XWPFDocument document, List<String> imageUrls) {
    List<ChunkResult> chunks = new ArrayList<>();
    StringBuilder currentBody = new StringBuilder();
    String currentTitle = null;
    String currentTag = null;
    int imageIndex = 0;

    for (XWPFParagraph paragraph
            : document.getParagraphs()) {
        String style = paragraph.getStyle();
        String text = paragraph.getText().trim();
        if (text.isEmpty()) continue;

        boolean isHeading = style != null
                && (style.startsWith("Heading")
                    || style.startsWith("heading")
                    || style.matches("\\d"));

        if (isHeading) {
            if (currentTitle != null
                    && currentBody.length()
                       >= MIN_CHUNK_LENGTH) {
                String imgUrl =
                        imageIndex < imageUrls.size()
                        ? imageUrls.get(imageIndex++)
                        : null;
                chunks.add(new ChunkResult(currentTitle,
                        currentTag,
                        currentBody.toString(), imgUrl));
                currentBody.setLength(0);
            }
            currentTitle = text;
            currentTag = inferTag(text);
            continue;
        }

        currentBody.append(text).append("\n");

        if (currentBody.length() > MAX_CHUNK_LENGTH) {
            String imgUrl =
                    imageIndex < imageUrls.size()
                    ? imageUrls.get(imageIndex++) : null;
            chunks.add(new ChunkResult(
                    currentTitle != null
                            ? currentTitle : "未分类知识点",
                    currentTag != null
                            ? currentTag : "通用",
                    currentBody.toString(), imgUrl));
            currentBody.setLength(0);
        }
    }

    if (currentBody.length() >= MIN_CHUNK_LENGTH) {
        chunks.add(new ChunkResult(
                currentTitle != null
                        ? currentTitle : "未分类知识点",
                currentTag != null ? currentTag : "通用",
                currentBody.toString(),
                imageIndex < imageUrls.size()
                        ? imageUrls.get(imageIndex)
                        : null));
    }

    return chunks;
}
```

### 6.3 Word 图片提取

```java
private List<String> extractAndUploadImages(
        XWPFDocument document, String filename) {
    List<String> urls = new ArrayList<>();
    String baseName = filename.replaceAll("\\.[^.]+$", "");
    int index = 0;

    for (XWPFPictureData picture
            : document.getAllPictures()) {
        try {
            byte[] data = picture.getData();
            String ext = picture.suggestFileExtension();
            String contentType = "image/" + ext;
            String objectName = String.format(
                    "knowledge/%s/img_%d.%s",
                    baseName, index++, ext);
            String url = minioService.uploadImage(
                    data, objectName, contentType);
            urls.add(url);
        } catch (Exception e) {
            log.warn("[Word Parser] extract image failed,"
                     + " index={}", index, e);
        }
    }

    log.info("[Word Parser] extracted {} images from {}",
             urls.size(), filename);
    return urls;
}
```

---

## 七、Controller 层扩展

### 7.1 修改 KnowledgeImportController

在现有 Controller 基础上扩展，支持 `.md` / `.pdf` / `.docx` 三种格式。PDF/DOCX 统一走异步处理（因 VL 图片描述生成耗时较长），Markdown 保持同步：

```java
@RestController
@RequestMapping("/admin/knowledge")
@Slf4j
public class KnowledgeImportController {

    @Resource
    private KnowledgeInitializer knowledgeInitializer;
    @Resource
    private List<DocumentParser> documentParsers;
    @Resource
    private KnowledgeImportAsyncService importAsyncService;

    @PostMapping("/import")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<String> importKnowledge(
            @RequestPart("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件不能为空");
        }

        String filename = file.getOriginalFilename();
        String extension = StringUtils.getFilenameExtension(filename);

        // Markdown 走原有逻辑（同步）
        if ("md".equalsIgnoreCase(extension) || "markdown".equalsIgnoreCase(extension)) {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            int count = knowledgeInitializer.parseAndStore(content);
            knowledgeInitializer.validateImportedCount(count);
            return ResultUtils.success("成功导入 " + count + " 条知识条目");
        }

        // PDF / Word 统一走异步处理（VL 图片描述生成耗时较长）
        DocumentParser parser = documentParsers.stream()
                .filter(p -> p.supports(extension))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAMS_ERROR,
                        "不支持的文件格式: " + extension + "，仅支持 .md / .pdf / .docx"));

        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        byte[] fileBytes = file.getBytes();
        importAsyncService.importAsync(taskId, fileBytes, filename, parser);
        return ResultUtils.success(taskId);
    }

    @GetMapping("/import/status/{taskId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<ImportTaskStatus> getImportStatus(@PathVariable String taskId) {
        ImportTaskStatus status = importAsyncService.getTaskStatus(taskId);
        if (status == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "任务不存在: " + taskId);
        }
        return ResultUtils.success(status);
    }
}
```

### 7.2 设计要点

1. 利用 Spring 的 `List<DocumentParser>` 自动注入所有实现类，通过 `supports()` 方法路由到对应解析器。
2. 新增格式不需要修改 Controller 代码，只需新增一个 `DocumentParser` 实现类（开闭原则）。
3. Markdown 仍走原有逻辑（同步），保持向后兼容。
4. PDF/DOCX 统一走 `KnowledgeImportAsyncService` 异步处理（`@Async` + `Semaphore(2)` 限制并发），返回 taskId 供前端轮询 `/import/status/{taskId}` 查询进度。不再有 10MB 阈值判断，因为 VL 图片描述生成耗时较长，所有 PDF/DOCX 都需要异步 + 进度条。
5. PDF/Word 使用 `parseWithImages()` 方法，同时提取文本和图片。图片上传 MinIO 后，由 VL 视觉模型（Qwen-VL）并发生成描述，写入 chunk 的 `image_refs` metadata。
6. `ImportTaskStatus` 包含 `progress`（0-100）和 `currentStep` 字段，前端通过进度条实时展示导入进度。

---

## 八、Metadata 扩展：image_urls 与 image_refs 字段

### 8.1 现有 metadata 结构

```
content_type: 知识点
tag: 二分查找
title: 二分查找基础模板与核心思想
```

### 8.2 扩展后 metadata 结构

```
content_type: 知识点
tag: 二分查找
title: 二分查找基础模板与核心思想
image_urls: http://minio:9000/oj-knowledge-images/xxx1.png,http://minio:9000/oj-knowledge-images/xxx2.png
image_refs: [{"url":"http://...xxx1.png","title":"二分查找基础模板与核心思想","tag":"二分查找","nearbyText":"图 10-2 二分查找示意图...","caption":"这张图展示了二分查找在有序数组中逐步缩小搜索范围的过程","page":3}]
source_type: pdf
```

新增字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| image_urls | String（可选） | 该知识块关联的图片 URL，多张图片以逗号分隔，存储在 MinIO `oj-knowledge-images` 桶 |
| image_refs | String（可选） | JSON 数组，每张图片的结构化语义信息（url/title/tag/nearbyText/caption/page），caption 由 VL 视觉模型自动生成，用于检索时精准匹配 |
| source_type | String（可选） | 来源格式：md / pdf / docx |

`image_refs` 相比 `image_urls` 的优势：
- `image_urls` 只有 URL，检索时无法判断图片与用户 query 的相关性，只能在"视觉类 query"时全量返回。
- `image_refs` 携带每张图片的上下文语义（标题、标签、附近文本、VL 生成的图片描述、页码），检索时可以按 query 相关性过滤，避免无关图片干扰 LLM。
- `caption` 字段由 VL 视觉模型（Qwen-VL）在导入时自动生成，描述图片内容（如"展示了快速排序的分区过程"），让 LLM 能理解图片画的是什么。

### 8.3 KnowledgeInitializer.parseBlock 适配

`parseBlock` 方法中已增加对 `image_urls` 和 `image_refs` 两个 metadata 字段的解析，并在 `buildSearchableText()` 中将图片信息写入可检索文本：

```java
// parseBlock 中提取可选 metadata 字段
String imageUrls = metadataMap.get("image_urls");
if (imageUrls != null && !imageUrls.isBlank()) {
    segmentMetadata.put("image_urls", imageUrls);
}
String imageRefs = metadataMap.get("image_refs");
if (imageRefs != null && !imageRefs.isBlank()) {
    segmentMetadata.put("image_refs", imageRefs);
}
String sourceType = metadataMap.get("source_type");
if (sourceType != null && !sourceType.isBlank()) {
    segmentMetadata.put("source_type", sourceType);
}

// buildSearchableText — 将图片语义信息写入 Embedding 文本
private String buildSearchableText(String title, String tag,
        String contentType, String sourceType,
        String imageUrls, String imageRefs, String body) {
    StringBuilder text = new StringBuilder();
    text.append("title: ").append(title).append("\n");
    text.append("tag: ").append(tag).append("\n");
    text.append("content_type: ").append(contentType).append("\n");
    if (sourceType != null && !sourceType.isBlank()) {
        text.append("source_type: ").append(sourceType).append("\n");
    }
    if (imageUrls != null && !imageUrls.isBlank()) {
        text.append("has_images: true\n");
    }
    if (imageRefs != null && !imageRefs.isBlank()) {
        text.append("image_context: ").append(imageRefs).append("\n");
    }
    text.append("\n").append(body);
    return text.toString();
}
```

> **设计要点**：`image_refs` 的 JSON 内容被写入 `image_context:` 行参与 Embedding 计算，使得向量空间中"含图片的 chunk"与图片语义相关的 query 距离更近，提升图片召回率。

### 8.4 RAG 检索结果返回图片

RAG 检索侧通过两层机制实现图片精准匹配：

**第一层：`ImageAwareContentRetriever` 装饰器**

包装 `oj_knowledge` 集合的 `EmbeddingStoreContentRetriever`，对每个检索结果调用 `RagImageSupport.appendRelevantImages()` 注入相关图片：

```java
public class ImageAwareContentRetriever implements ContentRetriever {

    private final ContentRetriever delegate;

    @Override
    public List<Content> retrieve(Query query) {
        return delegate.retrieve(query).stream()
                .map(content -> enrichWithImages(content, query.text()))
                .toList();
    }

    private Content enrichWithImages(Content content, String query) {
        TextSegment segment = content.textSegment();
        if (segment == null) return content;
        String enriched = RagImageSupport.appendRelevantImages(segment, query);
        return enriched.equals(segment.text())
                ? content
                : Content.from(TextSegment.from(enriched, segment.metadata()));
    }
}
```

**第二层：`RagImageSupport` 图片相关性过滤**

核心工具类，负责判断每张图片是否与用户 query 相关：

```java
public static String appendRelevantImages(TextSegment segment, String query) {
    List<ImageRef> refs = relevantImages(segment.metadata(), query, segment.text());
    if (refs.isEmpty()) return segment.text();
    StringBuilder sb = new StringBuilder(segment.text());
    sb.append("\n\n[RAG_SOURCE_IMAGES]");
    sb.append("\nOnly keep the following image links when they directly support the answer.");
    for (ImageRef ref : refs) {
        sb.append("\n![knowledge-image](").append(ref.url()).append(")");
    }
    return sb.toString();
}
```

图片相关性判断采用**双路径策略**：

| 路径 | 触发条件 | 过滤逻辑 |
|------|----------|----------|
| 新路径（`image_refs` 存在） | chunk 含 `image_refs` JSON metadata | 解析每张图片的语义文本（title + tag + nearbyText + caption），与 query 做术语重叠匹配，只返回相关图片 |
| 兼容路径（仅 `image_urls`） | chunk 只有 `image_urls`，无 `image_refs` | 仅当 query 包含视觉类关键词（"图"、"示意"、"流程"、"结构"等）时才返回图片，避免无关图片干扰 |

`isRelevant()` 三级判断：
1. query 归一化后是否为图片语义文本的子串（直接命中）
2. query 术语集与图片术语集是否有交集（领域术语匹配，含 dfs/bfs/dp/二叉树/排序等 30+ 算法领域词）
3. query 是否为视觉类 query 且图片附近文本与 chunk 文本有语义重叠（兜底）

**`ImageRef` 数据结构**：

```java
public record ImageRef(String url, String title, String tag,
                       String nearbyText, String caption, Integer page) {
    public String semanticText() {
        return String.join(" ", title, tag, caption, nearbyText);
    }
}
```
```

在 `AiModelHolder.buildRetrievalAugmentor()` 中，`ImageAwareContentRetriever` 包装了 `oj_knowledge` 的 base retriever，与 `oj_question` retriever 一起注册到 `DefaultQueryRouter`：

```java
EmbeddingStoreContentRetriever baseKnowledgeRetriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(embeddingStore).embeddingModel(this.embeddingModel)
        .maxResults(topK).minScore(minScore).build();

ImageAwareContentRetriever knowledgeRetriever =
        new ImageAwareContentRetriever(baseKnowledgeRetriever);

EmbeddingStoreContentRetriever questionRetriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(questionEmbeddingStore).embeddingModel(this.embeddingModel)
        .maxResults(topK).minScore(minScore).build();

return DefaultRetrievalAugmentor.builder()
        .queryRouter(new DefaultQueryRouter(knowledgeRetriever, questionRetriever))
        .build();
```

同时，System Prompt 中增加了【图片引用规范】，要求 LLM 原样保留 RAG 检索到的 markdown 图片链接，禁止修改或编造图片 URL。

---

## 九、Hello 算法 PDF 专项处理方案

### 9.1 Hello 算法的内容结构分析

Hello 算法（hello-algo.com）是一本结构非常规整的算法教材，内容特点：

1. 章节层级清晰：章 → 节 → 小节（如"第 6 章 哈希表 → 6.1 哈希表 → 6.1.1 哈希表常用操作"）
2. 图片丰富：每个知识点都配有数据结构示意图、算法执行过程动画截图
3. 代码示例多语言：Java / Python / C++ / Go 等，每个算法都有完整代码
4. 内容密度适中：每个小节通常 200~800 字，天然适合作为 RAG chunk

### 9.2 针对性切片策略

```java
// Hello 算法专用标题模式
private static final Pattern HELLO_ALGO_TITLE =
        Pattern.compile(
    "^(\\d+\\.\\d+(\\.\\d+)?\\s+.+|"
    + "第\\s*\\d+\\s*章\\s+.+)$"
);
```

切片规则：

1. 一级标题（"第 X 章"）：作为 tag 的来源，不单独成 chunk
2. 二级标题（"X.Y 标题"）：作为 chunk 的分割点
3. 三级标题（"X.Y.Z 标题"）：如果上一个 chunk 已经超过 400 字符，在此处切分；否则合并到上一个 chunk
4. 代码块：保留在 chunk 内，但如果代码块超过 300 字符，只保留前 300 字符 + "..."

### 9.3 图片与知识块的关联策略

Hello 算法的图片通常紧跟在对应知识点之后。关联策略：

1. 按页码顺序提取图片
2. 每个 chunk 记录其所在的页码范围
3. 将该页码范围内的图片 URL 关联到对应 chunk
4. 如果一个 chunk 跨多页，取第一页的图片

### 9.4 预期效果

以 Hello 算法 PDF 为例，预期处理结果：

```
输入：hello-algo.pdf（约 500 页）
输出：
  - 知识条目：约 200~300 个 chunk
  - 提取图片：约 300~500 张（上传至 MinIO）
  - 每个 chunk 平均 200~400 字符
  - tag 覆盖：数组、链表、栈、队列、哈希表、树、堆、
              图、排序算法、搜索算法、动态规划、贪心算法、
              回溯算法、分治算法等
处理耗时：约 2~5 分钟（主要耗时在 embedding 和图片上传）
```

---

## 十、前端管理页面适配

### 10.1 上传组件修改

现有的知识库导入页面需要修改文件类型限制：

```vue
<a-upload
  :accept="'.md,.markdown,.pdf,.docx'"
  :before-upload="beforeUpload"
>
  <a-button>上传知识文件</a-button>
</a-upload>
```

### 10.2 上传前校验

```javascript
const beforeUpload = (file) => {
  const ext = file.name.split('.').pop().toLowerCase();
  const allowedExts = ['md', 'markdown', 'pdf', 'docx'];

  if (!allowedExts.includes(ext)) {
    message.error('仅支持 .md / .pdf / .docx 格式');
    return false;
  }

  // PDF 允许 50MB，其他 10MB
  const maxSize = ext === 'pdf' ? 50 : 10;
  if (file.size / 1024 / 1024 > maxSize) {
    message.error(`文件大小不能超过 ${maxSize}MB`);
    return false;
  }

  return true;
};
```

---

## 十一、异常处理与边界情况

### 11.1 PDF 解析可能遇到的问题

| 问题 | 原因 | 处理方式 |
|---|---|---|
| 提取文本为空 | 扫描版 PDF（图片型） | 检测文本长度，若为空则提示"该 PDF 为扫描版，暂不支持" |
| 乱码 | 字体编码问题 | PDFBox 3.x 已大幅改善，极少出现；若出现则跳过该页 |
| 图片提取失败 | 图片格式不支持 | catch 异常后跳过，不影响文本导入 |
| 文件过大 | 超过内存限制 | 限制上传大小（建议 50MB），超大文件建议分章节上传 |
| 切片过碎 | 标题识别过于激进 | 设置 MIN_CHUNK_LENGTH 阈值，过短的 chunk 合并到上一个 |

### 11.2 Word 解析可能遇到的问题

| 问题 | 原因 | 处理方式 |
|---|---|---|
| 无标题样式 | 作者未使用 Word 标题样式 | fallback 到正则匹配标题模式（同 PDF 策略） |
| .doc 格式 | 旧版 Word 格式 | 仅支持 .docx，提示用户转换格式 |
| 表格内容 | Word 表格中的文本 | 提取表格单元格文本，拼接为纯文本 |

---

## 十二、实现优先级与开发计划

### 12.1 分阶段实施

**第一阶段（核心链路）：** ✅ 已完成

1. ✅ 引入 PDFBox + POI + MinIO 依赖
2. ✅ 实现 MinioConfig + MinioService
3. ✅ 实现 PdfDocumentParser（文本提取 + 自动切片 + 图片提取上传）
4. ✅ 实现 WordDocumentParser（文本提取 + 按标题切片 + 图片提取上传）
5. ✅ 修改 KnowledgeImportController 支持多格式路由 + 大文件异步处理
6. ✅ 测试：用 Hello 算法 PDF 验证切片效果

**第二阶段（图片 + RAG 感知）：** ✅ 已完成

1. ✅ PDF 图片提取 + MinIO 上传（`PdfDocumentParser.extractAndUploadImages()`）
2. ✅ Word 图片提取 + MinIO 上传（`WordDocumentParser.extractAndUploadImages()`）
3. ✅ 扩展 metadata 支持 `image_urls` 字段（逗号分隔多图）
4. ✅ `KnowledgeInitializer.parseBlock` 兼容新 metadata 字段
5. ✅ `ImageAwareContentRetriever` 装饰器实现 RAG 检索图片感知
6. ✅ System Prompt 增加【图片引用规范】

**第三阶段（体验优化）：** 已完成

1. ✅ 前端上传组件适配多格式
2. ✅ 上传结果摘要展示
3. ✅ PDF/DOCX 统一异步处理（`KnowledgeImportAsyncService`，`Semaphore(2)` 限并发），前端进度条实时展示
4. ✅ VL 视觉模型（Qwen-VL）自动生成图片描述，写入 `image_refs.caption`
5. 导入历史记录（可选，记录每次导入的文件名、条目数、时间）

### 12.2 测试验证清单

- [x] 上传 Hello 算法 PDF，验证切片数量和质量
- [x] 上传一份 .docx 算法笔记，验证标题识别和切片
- [x] 上传 .md 文件，验证原有逻辑不受影响
- [x] 上传空文件 / 损坏文件，验证错误提示
- [x] 检查 MinIO 中图片是否正确上传和可访问
- [x] RAG 检索验证：导入后搜索"二分查找"，确认能命中 PDF 中的相关内容
- [x] 检索结果中的 image_urls 是否正确返回，LLM 回答中是否保留图片链接
- [ ] 上传扫描版 PDF，验证提示信息（当前不支持 OCR，提取文本为空时会跳过）

---

## 十三、面试讲解要点

这个功能在面试中可以从以下角度展开：

1. **工程设计**：策略模式（`DocumentParser` 接口 + 多实现），新增格式零修改 Controller（开闭原则）。接口设计了 `parse()` 和 `parseWithImages()` 两个方法，后者返回 `ParseResult` record 同时携带文本和图片 URL 列表，default 实现保证向后兼容。
2. **文本切片**：不是简单按固定长度切，而是按语义结构（章节标题）切片，保证每个 chunk 的语义完整性。为什么 chunk 大小控制在 80~600 字符？太短信息量不足导致 embedding 质量低，太长超出 embedding 模型的有效编码窗口。
3. **图片处理**：PDF 图片提取用 PDFBox 的 `PDResources` API，不是按页渲染截图（那样会丢失分辨率），而是直接提取原始嵌入图片。图片缩放到 1600px 上传 MinIO 保证展示质量，同时缩放到 512px 用于 VL 模型描述生成以节省内存和 API 开销。
4. **VL 视觉模型集成**：导入时用 Qwen-VL 为每张图片生成一句话描述（caption），写入 `image_refs` metadata。VL 描述在两个环节发挥作用：导入时帮助将图片分配到语义最相关的 chunk（`hasMeaningfulOverlap`），检索时让 LLM 理解图片内容从而更准确地决定是否引用。`VisionModelHolder` 管理 VL 模型生命周期，支持热更新，分批并发调用（每批 20 张，并发数可配置）。
5. **RAG 图片感知**：通过 `ImageAwareContentRetriever` 装饰器模式包装 base retriever，检索到含图片的 chunk 时自动追加 `[RAG_SOURCE_IMAGES]` 段和 markdown 图片引用到 LLM 上下文，配合 System Prompt 中的【图片引用规范】，实现端到端的图文混合知识检索。
6. **存储选型**：为什么用 MinIO 而不是直接存数据库 BLOB？图片是二进制大对象，存数据库会拖慢查询性能；MinIO 兼容 S3 协议，生产环境可以无缝切换到阿里云 OSS / AWS S3。
7. **异步处理与进度追踪**：PDF/DOCX 统一走 `@Async` 异步导入，`Semaphore(2)` 限制并发防止 OOM，返回 taskId 供前端轮询状态。`ImportTaskStatus` 包含 `progress`（0-100）和 `currentStep` 字段，前端通过进度条实时展示（解析文档 → 上传图片 → 生成图片描述 → 写入向量库）。
8. **与现有系统的集成**：解析器只输出 markdown block 格式字符串，完全复用现有的 `parseAndStore()` → embedding → Milvus 链路，改动最小化。
