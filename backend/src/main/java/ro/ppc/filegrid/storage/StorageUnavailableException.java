package ro.ppc.filegrid.storage;

import org.springframework.http.HttpStatus;
import ro.ppc.filegrid.common.ApiException;

/** The backend for a category is down or timing out. Other categories are unaffected. */
public class StorageUnavailableException extends ApiException {

    public StorageUnavailableException(StorageBackendType backend, String operation, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "storage_unavailable",
                "Storage backend " + backend + " is unavailable (" + operation + "); retry later", cause);
    }
}
