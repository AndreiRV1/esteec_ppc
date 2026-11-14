package ro.ppc.filegrid.extraction;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ro.ppc.filegrid.common.ApiException;
import ro.ppc.filegrid.connection.DocumentFormat;
import ro.ppc.filegrid.storage.TempFiles;

/**
 * Form autofill: reads an uploaded PDF or photo and returns the form fields it found. Public, because
 * the /register form has no account yet. The document is only read: nothing is stored, logged or audited,
 * and the real upload still goes through the normal endpoints with their own validation.
 */
@RestController
@RequestMapping("/api/v1/public/document-extraction")
@Tag(name = "Document extraction")
public class DocumentExtractionController {

    static final long MAX_BYTES = 10L * 1024 * 1024; // same limit as a connection document

    public enum Kind { CONNECTION, FAULT }

    public record Extraction(DocumentText.Source source, Map<String, String> fields) {
    }

    private final DocumentText documentText;
    private final TempFiles tempFiles;

    public DocumentExtractionController(DocumentText documentText, TempFiles tempFiles) {
        this.documentText = documentText;
        this.tempFiles = tempFiles;
    }

    @Operation(summary = "Read form fields from a PDF (text layer or OCR) or a JPEG/PNG photo")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Extraction extract(@RequestParam Kind kind, @RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("invalid_document", "The document must be between 1 byte and 10 MB");
        }
        DocumentFormat format;
        try (InputStream in = file.getInputStream()) {
            format = DocumentFormat.detect(in).orElseThrow(() -> new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "unsupported_document", "Only PDF, JPEG and PNG documents can be read"));
        }
        Path copy = tempFiles.create("extract");
        try {
            file.transferTo(copy);
            DocumentText.Result text = documentText.read(copy, format);
            Map<String, String> fields = kind == Kind.CONNECTION
                    ? DocumentFields.connection(text.text()) : DocumentFields.fault(text.text());
            return new Extraction(text.source(), fields);
        } finally {
            TempFiles.deleteQuietly(copy);
        }
    }
}
