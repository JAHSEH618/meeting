package com.meeting.api.domain.document;

import com.meeting.api.client.common.ErrorCode;
import java.io.InputStream;
import java.util.List;

/**
 * Domain port for plain-text extraction from uploaded documents.
 *
 * Supported by phase 1: TXT, Markdown, plain PDF (text layer present), DOCX.
 * Scanned PDFs and standalone image files raise {@link DocumentParseException}
 * with {@link ErrorCode#DOCUMENT_OCR_UNSUPPORTED} — OCR is explicitly out of
 * scope for phase 1 per spec §4.2.
 */
public interface DocumentParser {
    ParsedDocument parse(String fileName, String contentType, InputStream content) throws DocumentParseException;

    record ParsedDocument(
        String detectedMediaType,
        String text,
        List<Page> pages,
        int totalPages
    ) {
    }

    record Page(int pageNumber, String text) {
    }

    final class DocumentParseException extends RuntimeException {
        private final ErrorCode errorCode;

        public DocumentParseException(ErrorCode errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public DocumentParseException(ErrorCode errorCode, String message, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
        }

        public ErrorCode errorCode() {
            return errorCode;
        }
    }
}
