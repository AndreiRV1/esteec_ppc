/** Types of the FileGrid API contract. The API layer (api.ts) is the only place that depends on them. */

export type Role = 'client' | 'admin' | 'employee';
export type Region = 'MUNTENIA' | 'BANAT' | 'DOBROGEA';
export const REGIONS: Region[] = ['MUNTENIA', 'BANAT', 'DOBROGEA'];

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

/** RFC 9457 problem document, as returned by every failing API call. */
export interface Problem {
  status: number;
  code?: string;
  detail?: string;
  title?: string;
  traceId?: string;
}

// ---- current user & consumption -------------------------------------------------

export interface Me {
  username: string;
  roles: string[];
  region: Region | null;
  county: string | null;
  locality: string | null;
  meterId: string | null;
}

export type Bucket = 'HOUR' | 'DAY';

export interface ConsumptionPoint {
  start: string;
  kwh: number;
  anomalies: number;
}

export interface Consumption {
  meterId: string;
  bucket: Bucket;
  points: ConsumptionPoint[];
}

export interface ConsumptionSummary {
  monthToDateKwh: number;
  previousMonthKwh: number;
  lastReadingAt: string | null;
}

// ---- faults ---------------------------------------------------------------------

export type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export const SEVERITIES: Severity[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

export interface Fault {
  id: string;
  region?: Region;
  county: string;
  locality: string | null;
  substationCode: string;
  occurredAt: string;
  restoredAt: string | null;
  severity: Severity;
  cause: string | null;
  affectedCustomers: number | null;
  description?: string | null;
  sizeBytes?: number;
  sha256?: string;
}

export interface FaultMetadata {
  region: Region;
  county: string;
  locality: string;
  substationCode: string;
  occurredAt: string;
  restoredAt?: string;
  severity: Severity;
  cause?: string;
  affectedCustomers?: number;
  description?: string;
}

export interface FaultFilter {
  region?: Region | '';
  county?: string;
  severity?: Severity | '';
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

/** Returned when a file-backed record is created. */
export interface StoredRef {
  id: string;
  fileId: string;
  sha256: string;
}

// ---- drone videos ---------------------------------------------------------------

export interface VideoMetadata {
  region: Region;
  county: string;
  lineCode: string;
  droneId?: string;
  flightStartedAt: string;
  durationSeconds?: number;
  notes?: string;
}

export interface VideoUploadInit extends VideoMetadata {
  filename: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
}

export interface UploadSession {
  uploadId: string;
  partSizeBytes: number;
}

export interface UploadStatus {
  status: string;
  bytesReceived: number;
  receivedParts: number[];
}

export interface DroneVideo extends VideoMetadata {
  id: string;
  sizeBytes?: number;
  sha256?: string;
  filename?: string;
  createdAt?: string;
}

export interface VideoFilter {
  region?: Region | '';
  lineCode?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

// ---- connection requests (racordare) --------------------------------------------

export type ApplicantType = 'PERSOANA_FIZICA' | 'PERSOANA_JURIDICA';
export type RequestType = 'LOC_CONSUM_NOU' | 'SPOR_PUTERE' | 'SEPARARE' | 'ACTUALIZARE_DATE';
export type RequestStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED';
export type PermitType = 'AVIZ_AMPLASAMENT' | 'CERTIFICAT_URBANISM';
export type Decision = 'ACCEPTED' | 'REJECTED';

export interface Applicant {
  fullName: string;
  identityNumber: string;
  email: string;
  phone: string;
  address: string;
}

export interface ConsumptionPlace {
  /** Two-letter county code (B, IF, TM, ...). */
  county: string;
  locality: string;
  address: string;
}

export interface Permit {
  type: PermitType;
  number: string;
  issuer: string;
  issuedOn: string;
  validUntil: string;
}

export interface ConnectionRequestForm {
  applicantType: ApplicantType;
  requestType: RequestType;
  applicant: Applicant;
  consumptionPlace: ConsumptionPlace;
  requestedPowerKw: number;
  permit: Permit;
}

export interface ConnectionFiles {
  cerereRacordare: File;
  actIdentitate: File;
  aviz: File;
}

export interface RequestSummary {
  clientId: string;
  region: Region;
  applicantType: ApplicantType;
  requestType: RequestType;
  county: string;
  locality: string;
  requestedPowerKw: number;
  status: RequestStatus;
  statusReason: string | null;
  createdAt: string;
  reviewedAt: string | null;
}

export interface RequestDocument {
  id: string;
  documentType: string;
  entryName: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  issuer: string | null;
  issuedOn: string | null;
  validUntil: string | null;
}

export interface ArchiveInfo {
  fileId: string;
  backend: StorageBackend;
  sizeBytes: number;
  sha256: string;
  encryption: string;
  storedAt: string;
  lastVerifiedAt: string | null;
}

export interface AdminRequestDetail {
  request: RequestSummary;
  submittedBy: string;
  reviewedBy: string | null;
  applicant: Applicant;
  consumptionPlace: ConsumptionPlace;
  permit: Permit;
  documents: RequestDocument[];
  archive: ArchiveInfo;
  otherRequestsFromApplicant: RequestSummary[];
}

export interface RequestFilter {
  status?: RequestStatus | '';
  region?: Region | '';
  page?: number;
  size?: number;
}

// ---- storage policy -------------------------------------------------------------

export type StorageBackend = 'DATABASE' | 'S3' | 'FTP';
export const BACKENDS: StorageBackend[] = ['DATABASE', 'S3', 'FTP'];
export type FileCategory = 'FAULT_RECORD' | 'CONNECTION_DOCUMENTS' | 'DRONE_VIDEO';

export interface StoragePolicyUpdate {
  backend: StorageBackend;
  /** S3 bucket or FTP directory; null for DATABASE. */
  target: string | null;
  encryptAtRest: boolean;
  maxSizeBytes: number;
}

export interface StoragePolicy extends StoragePolicyUpdate {
  category: FileCategory;
  region: Region;
  updatedAt: string;
  updatedBy: string;
}
