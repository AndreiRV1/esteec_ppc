package ro.ppc.filegrid.storage;

import java.nio.file.Path;

/**
 * Plaintext of a stored file whose checksums have just been verified, spooled
 * to a private temp file. Closing deletes the temp file.
 */
public record VerifiedContent(StoredFile file, Path path) implements AutoCloseable {

    @Override
    public void close() {
        TempFiles.deleteQuietly(path);
    }
}
