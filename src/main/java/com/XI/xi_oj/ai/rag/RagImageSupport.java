package com.XI.xi_oj.ai.rag;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RagImageSupport {

    public static final String IMAGE_URLS_KEY = "image_urls";
    public static final String IMAGE_REFS_KEY = "image_refs";

    private static final Pattern ASCII_TOKEN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_+-]{1,}");
    private static final List<String> DOMAIN_TERMS = List.of(
            "dfs", "bfs", "avl", "bst", "dp", "topk", "top-k",
            "\u904d\u5386", "\u524d\u5e8f", "\u4e2d\u5e8f", "\u540e\u5e8f", "\u5c42\u5e8f",
            "\u6df1\u5ea6\u4f18\u5148", "\u5e7f\u5ea6\u4f18\u5148", "\u9012\u5f52",
            "\u6808", "\u961f\u5217", "\u4e8c\u53c9\u6811", "\u6811", "\u56fe",
            "\u6570\u7ec4", "\u94fe\u8868", "\u6392\u5e8f", "\u5feb\u6392",
            "\u5feb\u901f\u6392\u5e8f", "\u5f52\u5e76", "\u5806", "\u54c8\u5e0c",
            "\u53cc\u6307\u9488", "\u52a8\u6001\u89c4\u5212", "\u8d2a\u5fc3",
            "\u56de\u6eaf", "\u5206\u6cbb", "\u65cb\u8f6c", "\u5e73\u8861",
            "\u5931\u8861", "\u54e8\u5175", "\u5212\u5206", "\u57fa\u51c6",
            "\u5feb\u901f\u9009\u62e9", "\u7b2ck\u4e2a", "\u989c\u8272\u5206\u7c7b",
            "\u8377\u5170\u56fd\u65d7", "partition", "pivot", "quickselect"
    );
    private static final Set<String> STOP_TERMS = Set.of(
            "\u76f8\u5173", "\u4ecb\u7ecd", "\u4e00\u4e2a", "\u4ec0\u4e48",
            "\u5982\u4f55", "\u4ee5\u53ca", "\u5e2e\u6211", "\u67e5\u627e",
            "\u5173\u4e8e", "the", "and"
    );
    private static final List<String> VISUAL_TERMS = List.of(
            "\u56fe", "\u56fe\u7247", "\u793a\u610f", "\u53ef\u89c6\u5316",
            "\u6d41\u7a0b", "\u7ed3\u6784", "\u914d\u56fe", "\u56fe\u89e3"
    );

    private RagImageSupport() {
    }

    public static String appendRelevantImages(TextSegment segment, String query) {
        if (segment == null) {
            return "";
        }
        String text = segment.text();
        List<ImageRef> refs = relevantImages(segment.metadata(), query, text);
        if (refs.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        sb.append("\n\n[RAG_SOURCE_IMAGES]");
        sb.append("\nOnly keep the following image links when they directly support the answer.");
        for (ImageRef ref : refs) {
            sb.append("\n![knowledge-image](").append(ref.url()).append(")");
        }
        return sb.toString();
    }

    public static List<ImageRef> relevantImages(Metadata metadata, String query, String segmentText) {
        if (metadata == null) {
            return List.of();
        }
        List<ImageRef> refs = parseImageRefs(metadata.getString(IMAGE_REFS_KEY));
        if (!refs.isEmpty()) {
            return refs.stream()
                    .filter(ref -> isRelevant(query, ref.semanticText(), segmentText))
                    .toList();
        }

        String imageUrls = metadata.getString(IMAGE_URLS_KEY);
        if (imageUrls == null || imageUrls.isBlank()) {
            return List.of();
        }
        // Legacy chunks only know URLs, not per-image meaning. Be conservative to avoid
        // attaching unrelated same-page images to a correctly matched text chunk.
        if (!queryLooksVisual(query)) {
            return List.of();
        }
        List<ImageRef> legacy = new ArrayList<>();
        for (String url : imageUrls.split(",")) {
            String trimmed = url.trim();
            if (!trimmed.isEmpty()) {
                legacy.add(new ImageRef(trimmed, "", "", "", "", null));
            }
        }
        return legacy;
    }

    public static String buildImageRefsJson(List<ImageRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return "";
        }
        JSONArray array = JSONUtil.createArray();
        for (ImageRef ref : refs) {
            JSONObject obj = JSONUtil.createObj()
                    .set("url", ref.url())
                    .set("title", nullToEmpty(ref.title()))
                    .set("tag", nullToEmpty(ref.tag()))
                    .set("nearbyText", nullToEmpty(ref.nearbyText()))
                    .set("caption", nullToEmpty(ref.caption()))
                    .set("page", ref.page());
            array.add(obj);
        }
        return array.toString();
    }

    public static List<ImageRef> parseImageRefs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JSONArray array = JSONUtil.parseArray(json);
            List<ImageRef> refs = new ArrayList<>();
            for (Object item : array) {
                JSONObject obj = item instanceof JSONObject
                        ? (JSONObject) item
                        : JSONUtil.parseObj(item);
                String url = obj.getStr("url");
                if (url == null || url.isBlank()) {
                    continue;
                }
                refs.add(new ImageRef(
                        url.trim(),
                        obj.getStr("title", ""),
                        obj.getStr("tag", ""),
                        obj.getStr("nearbyText", ""),
                        obj.getStr("caption", ""),
                        obj.getInt("page", null)
                ));
            }
            return refs;
        } catch (Exception e) {
            return List.of();
        }
    }

    public static boolean hasMeaningfulOverlap(String left, String right) {
        Set<String> leftTerms = terms(left);
        Set<String> rightTerms = terms(right);
        if (leftTerms.isEmpty() || rightTerms.isEmpty()) {
            return false;
        }
        int overlap = 0;
        for (String term : leftTerms) {
            if (rightTerms.contains(term)) {
                overlap++;
            }
        }
        return overlap >= 1;
    }

    private static boolean isRelevant(String query, String imageText, String segmentText) {
        if (imageText == null || imageText.isBlank()) {
            return false;
        }
        String normalizedQuery = normalize(query);
        String normalizedImage = normalize(imageText);
        if (!normalizedQuery.isBlank() && normalizedImage.contains(normalizedQuery)) {
            return true;
        }
        Set<String> queryTerms = terms(query);
        Set<String> imageTerms = terms(imageText);
        if (!queryTerms.isEmpty() && !imageTerms.isEmpty()) {
            int overlap = 0;
            for (String term : queryTerms) {
                if (imageTerms.contains(term)) {
                    overlap++;
                }
            }
            if (overlap >= 1) {
                return true;
            }
        }
        // If the image metadata only has local text and the user query is broad, allow
        // images that are tied to the retrieved segment's main terms.
        return queryLooksVisual(query) && hasMeaningfulOverlap(segmentText, imageText);
    }

    private static boolean queryLooksVisual(String query) {
        String q = normalize(query);
        return VISUAL_TERMS.stream().anyMatch(q::contains);
    }

    private static Set<String> terms(String text) {
        String normalized = normalize(text);
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = ASCII_TOKEN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group().toLowerCase(Locale.ROOT);
            if (!isStopTerm(token)) {
                terms.add(token);
            }
        }
        for (String term : DOMAIN_TERMS) {
            String normalizedTerm = term.toLowerCase(Locale.ROOT);
            if (normalized.contains(normalizedTerm) && !isStopTerm(normalizedTerm)) {
                terms.add(normalizedTerm);
            }
        }
        return terms;
    }

    private static boolean isStopTerm(String token) {
        return token == null || token.length() < 2 || STOP_TERMS.contains(token);
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record ImageRef(String url,
                           String title,
                           String tag,
                           String nearbyText,
                           String caption,
                           Integer page) {
        public String semanticText() {
            return String.join(" ",
                    nullToEmpty(title),
                    nullToEmpty(tag),
                    nullToEmpty(caption),
                    nullToEmpty(nearbyText));
        }
    }
}
