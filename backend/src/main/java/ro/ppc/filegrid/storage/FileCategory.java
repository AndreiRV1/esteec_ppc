package ro.ppc.filegrid.storage;

/** File categories routed through {@code storage_policy}. Meter readings live in their own tables. */
public enum FileCategory {
    FAULT_RECORD,
    CONNECTION_DOCUMENTS,
    DRONE_VIDEO
}
