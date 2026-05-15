package com.meeting.api.infrastructure.gateway.document;

import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.document.DocumentParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Phase-1 plain-text document parser.
 *
 * <p>Implements the {@link DocumentParser} port for TXT / Markdown / plain PDFs with
 * a text layer / DOCX. The implementation deliberately avoids pulling Apache Tika or
 * a PDF engine into the build for now — instead it does best-effort plain-text
 * extraction for TXT/Markdown and rejects PDF / DOCX / image inputs with explicit
 * error codes so the surrounding workflow stays correct end-to-end. The next
 * phase swaps the rejection branches for a real Tika facade without changing the
 * port contract.</p>
 */
@Component
public class PlainTextDocumentParser implements DocumentParser {

    private static final Set<String> TEXT_MEDIA_TYPES = Set.of(
        "text/plain",
        "text/markdown",
        "text/x-markdown"
    );

    private static final Set<String> IMAGE_MEDIA_TYPES = Set.of(
        "image/png",
        "image/jpeg",
        "image/gif",
        "image/tiff",
        "image/webp"
    );

    @Override
    public ParsedDocument parse(String fileName, String contentType, InputStream content) throws DocumentParseException {
        if (content == null) {
            throw new DocumentParseException(ErrorCode.DOCUMENT_PARSE_FAILED, "content stream is null");
        }
        String mediaType = (contentType == null || contentType.isBlank())
            ? guessFromFileName(fileName)
            : contentType.toLowerCase(Locale.ROOT);
        try {
            if (IMAGE_MEDIA_TYPES.contains(mediaType)) {
                throw new DocumentParseException(ErrorCode.DOCUMENT_OCR_UNSUPPORTED,
                    "image OCR is not supported in phase 1: " + fileName);
            }
            if ("application/pdf".equals(mediaType)) {
                byte[] bytes = content.readAllBytes();
                if (looksLikeScannedPdf(bytes)) {
                    throw new DocumentParseException(ErrorCode.DOCUMENT_OCR_UNSUPPORTED,
                        "scanned PDF OCR is not supported in phase 1: " + fileName);
                }
                String extracted = extractPlainTextFromPdf(bytes);
                if (extracted.isBlank()) {
                    throw new DocumentParseException(ErrorCode.DOCUMENT_OCR_UNSUPPORTED,
                        "PDF has no extractable text layer (likely scanned): " + fileName);
                }
                return new ParsedDocument(mediaType, extracted, List.of(new Page(1, extracted)), 1);
            }
            if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mediaType)
                || (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".docx"))) {
                // Without Tika in the build, defer DOCX to phase-1+; emit a stable error so
                // the upstream service can mark the document as PARSE_FAILED rather than silently
                // succeeding with empty text.
                throw new DocumentParseException(ErrorCode.DOCUMENT_TYPE_UNSUPPORTED,
                    "DOCX parsing requires the optional Tika dependency, which is not bundled yet: " + fileName);
            }
            if (TEXT_MEDIA_TYPES.contains(mediaType) || mediaType.startsWith("text/")) {
                String text = new String(content.readAllBytes(), StandardCharsets.UTF_8);
                List<Page> pages = splitByFormFeed(text);
                return new ParsedDocument(mediaType, text, pages, pages.size());
            }
            throw new DocumentParseException(ErrorCode.DOCUMENT_TYPE_UNSUPPORTED,
                "unsupported document media type: " + mediaType);
        } catch (DocumentParseException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new DocumentParseException(ErrorCode.DOCUMENT_PARSE_FAILED, "failed to read document bytes", ex);
        }
    }

    private static List<Page> splitByFormFeed(String text) {
        List<Page> pages = new ArrayList<>();
        String[] parts = text.split("\f");
        for (int i = 0; i < parts.length; i++) {
            pages.add(new Page(i + 1, parts[i]));
        }
        return pages;
    }

    private static String guessFromFileName(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    /**
     * Heuristic: scanned PDFs have very few text-bearing operators (BT/ET).
     * If the binary contains <3 of them, assume image-only.
     */
    static boolean looksLikeScannedPdf(byte[] bytes) {
        int count = 0;
        for (int i = 0; i + 1 < bytes.length; i++) {
            if (bytes[i] == 'B' && bytes[i + 1] == 'T') count++;
            if (count >= 3) return false;
        }
        return true;
    }

    /**
     * Extract text from a PDF only when the file embeds a /Stream block in plaintext form
     * (raw TJ/Tj operators). Anything more complex requires the optional PDF engine and
     * surfaces as DOCUMENT_OCR_UNSUPPORTED or DOCUMENT_PARSE_FAILED upstream.
     */
    static String extractPlainTextFromPdf(byte[] bytes) {
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        StringBuilder out = new StringBuilder();
        int idx = 0;
        while (true) {
            int paren = raw.indexOf('(', idx);
            if (paren < 0) break;
            int end = raw.indexOf(')', paren + 1);
            if (end < 0) break;
            String chunk = raw.substring(paren + 1, end);
            if (chunk.length() > 0 && chunk.length() < 500) {
                out.append(chunk).append(' ');
            }
            idx = end + 1;
        }
        return out.toString().trim();
    }

    /**
     * Convenience overload used by tests.
     */
    public ParsedDocument parseBytes(String fileName, String contentType, byte[] bytes) {
        return parse(fileName, contentType, new ByteArrayInputStream(bytes));
    }
}
