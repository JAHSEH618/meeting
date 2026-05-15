package com.meeting.api;

import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.document.DocumentParser;
import com.meeting.api.infrastructure.gateway.document.PlainTextDocumentParser;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlainTextDocumentParserTest {

    private final PlainTextDocumentParser parser = new PlainTextDocumentParser();

    @Test
    void parsesTxtToSinglePageText() {
        var parsed = parser.parseBytes("notes.txt", "text/plain", "hello\nworld".getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.detectedMediaType()).isEqualTo("text/plain");
        assertThat(parsed.text()).isEqualTo("hello\nworld");
        assertThat(parsed.pages()).hasSize(1);
        assertThat(parsed.pages().get(0).pageNumber()).isEqualTo(1);
        assertThat(parsed.totalPages()).isEqualTo(1);
    }

    @Test
    void splitsFormFeedIntoPages() {
        var parsed = parser.parseBytes("doc.md", "text/markdown",
            "# Page one\nbody\fSecond page".getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.pages()).hasSize(2);
        assertThat(parsed.pages().get(0).text()).contains("Page one");
        assertThat(parsed.pages().get(1).text()).contains("Second page");
    }

    @Test
    void rejectsImageMimeTypeWithOcrUnsupported() {
        assertThatThrownBy(() -> parser.parseBytes("scan.png", "image/png", new byte[]{1, 2, 3}))
            .isInstanceOf(DocumentParser.DocumentParseException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.DOCUMENT_OCR_UNSUPPORTED);
    }

    @Test
    void rejectsScannedLookingPdfWithOcrUnsupported() {
        // No "BT" operators -> looksLikeScannedPdf returns true.
        byte[] bytes = "%PDF-1.5\nbinary garbage with no text".getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> parser.parseBytes("scan.pdf", "application/pdf", bytes))
            .isInstanceOf(DocumentParser.DocumentParseException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.DOCUMENT_OCR_UNSUPPORTED);
    }

    @Test
    void extractsPlainPdfWithTextLayer() {
        // Fake but valid-looking PDF with TJ-style text operators.
        String raw = "%PDF-1.5\nBT\nBT\nBT\n(Hello)Tj\n(World)Tj\n(Today is fine)Tj\nET\n";
        var parsed = parser.parseBytes("plain.pdf", "application/pdf", raw.getBytes(StandardCharsets.ISO_8859_1));

        assertThat(parsed.text()).contains("Hello");
        assertThat(parsed.text()).contains("World");
        assertThat(parsed.totalPages()).isEqualTo(1);
    }

    @Test
    void rejectsDocxWithExplicitErrorCode() {
        assertThatThrownBy(() -> parser.parseBytes("paper.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{0x50, 0x4B}))
            .isInstanceOf(DocumentParser.DocumentParseException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.DOCUMENT_TYPE_UNSUPPORTED);
    }

    @Test
    void rejectsUnknownMediaType() {
        assertThatThrownBy(() -> parser.parseBytes("file.xyz", "application/x-zip", new byte[]{0}))
            .isInstanceOf(DocumentParser.DocumentParseException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.DOCUMENT_TYPE_UNSUPPORTED);
    }

    @Test
    void inferMediaTypeFromFileExtension() {
        var parsed = parser.parseBytes("readme.md", null,
            "# title\n\ntext".getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.detectedMediaType()).isEqualTo("text/markdown");
    }
}
