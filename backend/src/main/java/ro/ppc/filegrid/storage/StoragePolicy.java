package ro.ppc.filegrid.storage;

import ro.ppc.filegrid.common.Region;

/** One row of {@code storage_policy}: where and how a category is written in a region. */
public record StoragePolicy(
        FileCategory category,
        Region region,
        StorageBackendType backend,
        String target,
        boolean encryptAtRest,
        long maxSizeBytes) {
}
