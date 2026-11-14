package ro.ppc.filegrid.extraction;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ro.ppc.filegrid.common.ApiException;
import ro.ppc.filegrid.connection.DocumentFormat;
import ro.ppc.filegrid.storage.TempFiles;

/**
 * Turns an uploaded document into plain text: the PDF's own text layer when it has one,
 * otherwise OCR (Tesseract) of the rendered pages or of the photo. Nothing is stored.
 */
@Component
public class DocumentText {

    public enum Source { TEXT, OCR, NONE }

    public record Result(String text, Source source) {
    }

    static final int MAX_PAGES = 2;
    private static final int MIN_TEXT_CHARS = 40; // less than this: a scan with no real text layer
    private static final int OCR_DPI = 300;
    private static final Logger log = LoggerFactory.getLogger(DocumentText.class);

    private final TempFiles tempFiles;
    private final String tesseract;
    // OCR is CPU-heavy and the endpoint is public: at most two run at once, the rest get 503.
    private final Semaphore ocrSlots = new Semaphore(2);

    public DocumentText(TempFiles tempFiles, @Value("${filegrid.ocr.tesseract:tesseract}") String tesseract) {
        this.tempFiles = tempFiles;
        this.tesseract = tesseract;
    }

    public Result read(Path file, DocumentFormat format) throws IOException {
        if (format != DocumentFormat.PDF) {
            return ocr(List.of(file));
        }
        try (PDDocument pdf = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            String text = textLayer(pdf);
            if (text.strip().length() >= MIN_TEXT_CHARS) {
                return new Result(text, Source.TEXT);
            }
            List<Path> pages = new ArrayList<>();
            try {
                PDFRenderer renderer = new PDFRenderer(pdf);
                for (int i = 0; i < Math.min(MAX_PAGES, pdf.getNumberOfPages()); i++) {
                    BufferedImage image = renderer.renderImageWithDPI(i, OCR_DPI, ImageType.GRAY);
                    Path page = tempFiles.create("ocr-page");
                    pages.add(page);
                    ImageIO.write(image, "png", page.toFile());
                }
                return ocr(pages);
            } finally {
                pages.forEach(TempFiles::deleteQuietly);
            }
        } catch (InvalidPasswordException e) {
            return new Result("", Source.NONE);
        }
    }

    /** Text in reading order, skipping rotated glyphs (a diagonal "SPECIMEN" watermark would split every line). */
    static String textLayer(PDDocument pdf) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition text) {
                Matrix m = text.getTextMatrix();
                if (Math.abs(m.getShearX()) < 0.01 && Math.abs(m.getShearY()) < 0.01) {
                    super.processTextPosition(text);
                }
            }
        };
        stripper.setSortByPosition(true);
        stripper.setEndPage(MAX_PAGES);
        return stripper.getText(pdf);
    }

    private Result ocr(List<Path> images) throws IOException {
        if (!ocrSlots.tryAcquire()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ocr_busy", "Document reading is busy; try again shortly");
        }
        try {
            StringBuilder text = new StringBuilder();
            for (Path image : images) {
                Path out = tempFiles.create("ocr-text");
                try {
                    // psm 6: one uniform block, which keeps each "label  value" table row on one line.
                    Process process = new ProcessBuilder(tesseract, image.toString(), "stdout", "-l", "ron+eng", "--psm", "6")
                            .redirectOutput(out.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                        process.destroyForcibly();
                        return new Result("", Source.NONE);
                    }
                    text.append(Files.readString(out, StandardCharsets.UTF_8)).append('\n');
                } finally {
                    TempFiles.deleteQuietly(out);
                }
            }
            return new Result(text.toString(), Source.OCR);
        } catch (IOException e) {
            // Tesseract missing (e.g. backend run from the IDE): the form is filled by hand.
            log.warn("OCR unavailable: {}", e.toString());
            return new Result("", Source.NONE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result("", Source.NONE);
        } finally {
            ocrSlots.release();
        }
    }
}
