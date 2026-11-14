import { Pipe, PipeTransform } from '@angular/core';
import { Region } from './models';

/** Romanian labels for the API's enum values. */
const LABELS: Record<string, string> = {
  MUNTENIA: 'Muntenia', BANAT: 'Banat', DOBROGEA: 'Dobrogea',
  PENDING: 'În așteptare', ACCEPTED: 'Aprobată', REJECTED: 'Respinsă',
  LOW: 'Scăzută', MEDIUM: 'Medie', HIGH: 'Ridicată', CRITICAL: 'Critică',
  PERSOANA_FIZICA: 'Persoană fizică', PERSOANA_JURIDICA: 'Persoană juridică',
  LOC_CONSUM_NOU: 'Loc de consum nou', SPOR_PUTERE: 'Spor de putere',
  SEPARARE: 'Separare', ACTUALIZARE_DATE: 'Actualizare date',
  AVIZ_AMPLASAMENT: 'Aviz de amplasament', CERTIFICAT_URBANISM: 'Certificat de urbanism',
  CERERE_RACORDARE: 'Cerere de racordare', ACT_IDENTITATE: 'Act de identitate',
  DATABASE: 'Bază de date', S3: 'S3', FTP: 'FTP',
  METER_READING: 'Citiri contoare',
  FAULT_RECORD: 'Înregistrări de avarie', CONNECTION_DOCUMENTS: 'Documente de racordare',
  DRONE_VIDEO: 'Video-uri dronă',
  NONE: 'Necriptat', AES_256_GCM: 'AES-256-GCM',
};

export const label = (value: string | null | undefined): string => (value ? (LABELS[value] ?? value) : '—');

@Pipe({ name: 'label' })
export class LabelPipe implements PipeTransform {
  transform = label;
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return '—';
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toLocaleString('ro-RO', { maximumFractionDigits: value < 10 && unit > 0 ? 1 : 0 })} ${units[unit]}`;
}

@Pipe({ name: 'bytes' })
export class BytesPipe implements PipeTransform {
  transform = formatBytes;
}

/** Counties served by the operator. `code` is what the connection-request API expects. */
export const COUNTIES: { code: string; name: string; region: Region }[] = [
  { code: 'B', name: 'București', region: 'MUNTENIA' },
  { code: 'IF', name: 'Ilfov', region: 'MUNTENIA' },
  { code: 'GR', name: 'Giurgiu', region: 'MUNTENIA' },
  { code: 'TM', name: 'Timiș', region: 'BANAT' },
  { code: 'AR', name: 'Arad', region: 'BANAT' },
  { code: 'CS', name: 'Caraș-Severin', region: 'BANAT' },
  { code: 'HD', name: 'Hunedoara', region: 'BANAT' },
  { code: 'CT', name: 'Constanța', region: 'DOBROGEA' },
  { code: 'TL', name: 'Tulcea', region: 'DOBROGEA' },
  { code: 'CL', name: 'Călărași', region: 'DOBROGEA' },
  { code: 'IL', name: 'Ialomița', region: 'DOBROGEA' },
];

export const countiesOf = (region: Region | '' | undefined) =>
  COUNTIES.filter((county) => !region || county.region === region);

/** `<input type="date">` value (local day) for a Date. */
export function toDateInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** Start of a local day given as yyyy-MM-dd, plus `addDays`, as ISO-8601 UTC. Empty input gives undefined. */
export function dayToIso(day: string, addDays = 0): string | undefined {
  if (!day) return undefined;
  const [y, m, d] = day.split('-').map(Number);
  return new Date(y, m - 1, d + addDays).toISOString();
}

/** `<input type="datetime-local">` value (local time) as ISO-8601 UTC. */
export const localToIso = (value: string): string | undefined => (value ? new Date(value).toISOString() : undefined);
