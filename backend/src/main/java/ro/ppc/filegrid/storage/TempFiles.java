package ro.ppc.filegrid.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spool files for uploads and verified downloads. They let FileGrid handle large
 * files with constant memory, and always hold ciphertext or verified plaintext
 * for the shortest possible time.
 */
public class TempFiles {

    private static final Logger log = LoggerFactory.getLogger(TempFiles.class);

    private final Path dir;

    public TempFiles(Path dir) {
        this.dir = dir;
    }

    public Path create(String prefix) {
        try {
            Path file = Files.createTempFile(dir, prefix, ".tmp");
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX file system
            }
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create temp file in " + dir, e);
        }
    }

    public static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete temp file {}: {}", file, e.toString());
        }
    }
}
