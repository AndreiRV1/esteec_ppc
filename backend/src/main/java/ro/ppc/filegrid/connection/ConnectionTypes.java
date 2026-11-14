package ro.ppc.filegrid.connection;

/** Enumerations of the connection-request domain; values match the database CHECK constraints. */
public final class ConnectionTypes {

    private ConnectionTypes() {
    }

    public enum ApplicantType {
        /** Natural person; identified by CNP. */
        PERSOANA_FIZICA,
        /** Legal entity; identified by CUI. */
        PERSOANA_JURIDICA
    }

    /** What the applicant asks for, as listed on the standard connection form. */
    public enum RequestType {
        LOC_CONSUM_NOU,
        SPOR_PUTERE,
        SEPARARE,
        ACTUALIZARE_DATE
    }

    public enum Status {
        PENDING,
        ACCEPTED,
        REJECTED
    }

    public enum Decision {
        ACCEPTED,
        REJECTED
    }

    /** The permit the applicant attaches as "aviz". */
    public enum PermitType {
        /** Issued by the distribution operator's branch. */
        AVIZ_AMPLASAMENT,
        /** Issued by the town hall / county council. */
        CERTIFICAT_URBANISM;

        DocumentType documentType() {
            return DocumentType.valueOf(name());
        }
    }

    public enum DocumentType {
        CERERE_RACORDARE("cerere-racordare"),
        ACT_IDENTITATE("act-identitate"),
        AVIZ_AMPLASAMENT("aviz-amplasament"),
        CERTIFICAT_URBANISM("certificat-urbanism");

        private final String slug;

        DocumentType(String slug) {
            this.slug = slug;
        }

        public String slug() {
            return slug;
        }
    }
}
