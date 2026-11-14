package ro.ppc.filegrid.storage;

/** The metadata says the file is stored, but the backend no longer has it. Treated as corruption. */
public class MissingObjectException extends RuntimeException {

    public MissingObjectException(ObjectStore.Address address, Throwable cause) {
        super("Object for file " + address.fileId() + " is missing at " + address.location(), cause);
    }
}
