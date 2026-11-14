package ro.ppc.filegrid.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.ppc.filegrid.config.FileGridProperties;

/**
 * vsftpd. Location format: absolute path inside the FTP chroot.
 * Uploads go to {@code <path>.part} and are renamed when complete, so a reader
 * never sees a half-written file. One connection per operation keeps failures isolated.
 */
@Component
public class FtpObjectStore implements ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(FtpObjectStore.class);

    private final FileGridProperties.Ftp config;

    public FtpObjectStore(FileGridProperties properties) {
        this.config = properties.storage().ftp();
    }

    @Override
    public StorageBackendType type() {
        return StorageBackendType.FTP;
    }

    @Override
    public void put(Address address, Path source, long size) {
        String path = address.location();
        String partial = path + ".part";
        FTPClient ftp = connect("put");
        try {
            makeParentDirectories(ftp, path);
            try (InputStream in = Files.newInputStream(source)) {
                if (!ftp.storeFile(partial, in)) {
                    throw new IOException("STOR failed: " + ftp.getReplyString());
                }
            }
            if (!ftp.rename(partial, path)) {
                throw new IOException("RNTO failed: " + ftp.getReplyString());
            }
        } catch (IOException e) {
            quietly(() -> ftp.deleteFile(partial));
            throw new StorageUnavailableException(type(), "put", e);
        } finally {
            disconnect(ftp);
        }
    }

    @Override
    public String beginParts(Address address) {
        FTPClient ftp = connect("put");
        try {
            makeParentDirectories(ftp, address.location());
            quietly(() -> ftp.deleteFile(address.location() + ".part"));
            return null;
        } catch (IOException e) {
            throw new StorageUnavailableException(type(), "put", e);
        } finally {
            disconnect(ftp);
        }
    }

    @Override
    public void putPart(Address address, String uploadId, int partNumber, long offset, Path source, long size) {
        FTPClient ftp = connect("put");
        try (InputStream in = Files.newInputStream(source)) {
            // REST + STOR writes at the offset, so a part that was cut off mid-way is simply written again.
            ftp.setRestartOffset(offset);
            if (!ftp.storeFile(address.location() + ".part", in)) {
                throw new IOException("STOR failed: " + ftp.getReplyString());
            }
        } catch (IOException e) {
            throw new StorageUnavailableException(type(), "put", e);
        } finally {
            disconnect(ftp);
        }
    }

    @Override
    public void completeParts(Address address, String uploadId) {
        String path = address.location();
        FTPClient ftp = connect("put");
        try {
            if (!ftp.rename(path + ".part", path)) {
                String reply = ftp.getReplyString();
                // Already renamed by an earlier attempt?
                if (ftp.listFiles(path).length != 1) {
                    throw new IOException("RNTO failed: " + reply);
                }
            }
        } catch (IOException e) {
            throw new StorageUnavailableException(type(), "put", e);
        } finally {
            disconnect(ftp);
        }
    }

    @Override
    public void abortParts(Address address, String uploadId) {
        FTPClient ftp = connect("delete");
        try {
            quietly(() -> ftp.deleteFile(address.location() + ".part"));
        } finally {
            disconnect(ftp);
        }
    }

    @Override
    public InputStream open(Address address) {
        FTPClient ftp = connect("get");
        try {
            InputStream data = ftp.retrieveFileStream(address.location());
            if (data == null) {
                int reply = ftp.getReplyCode();
                disconnect(ftp);
                if (reply == FTPReply.FILE_UNAVAILABLE) {
                    throw new MissingObjectException(address, null);
                }
                throw new IOException("RETR failed: " + reply);
            }
            return new FilterInputStream(data) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                        ftp.completePendingCommand();
                    } finally {
                        disconnect(ftp);
                    }
                }
            };
        } catch (IOException e) {
            disconnect(ftp);
            throw new StorageUnavailableException(type(), "get", e);
        }
    }

    @Override
    public void delete(Address address) {
        FTPClient ftp = connect("delete");
        try {
            if (!ftp.deleteFile(address.location()) && ftp.getReplyCode() != FTPReply.FILE_UNAVAILABLE) {
                throw new IOException("DELE failed: " + ftp.getReplyString());
            }
        } catch (IOException e) {
            throw new StorageUnavailableException(type(), "delete", e);
        } finally {
            disconnect(ftp);
        }
    }

    private FTPClient connect(String operation) {
        FTPClient ftp = new FTPClient();
        ftp.setConnectTimeout((int) config.connectTimeout().toMillis());
        ftp.setDefaultTimeout((int) config.dataTimeout().toMillis());
        ftp.setDataTimeout(config.dataTimeout());
        try {
            ftp.connect(config.host(), config.port());
            if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
                throw new IOException("FTP server refused connection: " + ftp.getReplyString());
            }
            ftp.setSoTimeout((int) config.dataTimeout().toMillis());
            if (!ftp.login(config.username(), config.password())) {
                throw new IOException("FTP login failed: " + ftp.getReplyString());
            }
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            ftp.enterLocalPassiveMode();
            return ftp;
        } catch (IOException e) {
            disconnect(ftp);
            throw new StorageUnavailableException(type(), operation, e);
        }
    }

    private static void makeParentDirectories(FTPClient ftp, String path) throws IOException {
        String[] parts = path.split("/");
        StringBuilder dir = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            dir.append('/').append(parts[i]);
            // MKD on an existing directory fails with 550; that's fine.
            ftp.makeDirectory(dir.toString());
        }
    }

    private static void disconnect(FTPClient ftp) {
        if (ftp.isConnected()) {
            quietly(ftp::logout);
            quietly(() -> {
                ftp.disconnect();
                return true;
            });
        }
    }

    private interface IoAction {
        boolean run() throws IOException;
    }

    private static void quietly(IoAction action) {
        try {
            action.run();
        } catch (IOException e) {
            log.debug("Ignoring FTP cleanup failure: {}", e.toString());
        }
    }
}
