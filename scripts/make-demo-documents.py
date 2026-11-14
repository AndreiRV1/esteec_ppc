#!/usr/bin/env python3
"""Generates the demo documents in demo/documents/ (PDF, A4) with headless Chromium.

Every document is a SPECIMEN with fictitious data, matching the values in docs/demo.md:
  cerere-racordare.pdf           the connection request form (cerereRacordare)
  act-identitate.pdf             ID card copy of a natural person (actIdentitate, PERSOANA_FIZICA)
  certificat-inregistrare.pdf    company registration certificate (actIdentitate, PERSOANA_JURIDICA)
  aviz-amplasament.pdf           site permit (aviz, AVIZ_AMPLASAMENT)
  certificat-urbanism.pdf        urban planning certificate (aviz, CERTIFICAT_URBANISM)
  cerere-racordare-firma.pdf     connection request of a company (spor de putere)
and for the fault-record upload, in demo/faults/:
  avarie-voluntari.pdf           fault report matching avarie-voluntari.json (Muntenia)
  avarie-constanta.pdf           fault report, Dobrogea, supply already restored
Every document the form can read back (OCR autofill) also gets a "-scanata" copy: the page rendered to a
slightly rotated, noisy grayscale image with no text layer, so only OCR can read it.

Usage: scripts/make-demo-documents.py   (needs chromium or google-chrome, pdftoppm and Pillow)
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "demo" / "documents"
FAULTS = ROOT / "demo" / "faults"
# Documents that also get an image-only "-scanata" copy for the OCR demo.
SCANNED = ["cerere-racordare.pdf", "aviz-amplasament.pdf", "avarie-voluntari.pdf"]

APPLICANT = {
    "name": "Ion Demo", "cnp": "1960529460012", "email": "ion.demo@example.ro", "phone": "+40 721 000 000",
    "address": "Str. Exemplului nr. 1, Voluntari, jud. Ilfov",
}
COMPANY = {"name": "Demo Energie SRL", "cui": "RO18547290", "reg": "J23/1234/2020",
           "address": "Bd. Demonstrației nr. 10, Voluntari, jud. Ilfov"}
PLACE = {"county": "Ilfov", "locality": "Voluntari", "address": "Str. Exemplului nr. 2"}
PERMIT = {"aa": "AA-2026-0042", "cu": "CU-2026-0815", "issued": "01.11.2026", "valid": "01.11.2027"}

STYLE = """
@page { size: A4; margin: 16mm 18mm; }
body { font: 10.5pt/1.4 'DejaVu Sans', Arial, sans-serif; color: #1b2836; }
h1 { font-size: 16pt; text-align: center; margin: 3mm 0 1mm; text-transform: uppercase; letter-spacing: .02em; }
.sub { text-align: center; color: #5b6878; margin: 0 0 5mm; }
.head { display: flex; justify-content: space-between; font-size: 9.5pt; color: #5b6878; border-bottom: 1px solid #c9d2dc; padding-bottom: 3mm; }
table { width: 100%; border-collapse: collapse; margin: 4mm 0; }
td { border: 1px solid #c9d2dc; padding: 1.6mm 3mm; vertical-align: top; }
td:first-child { width: 38%; background: #f4f6f8; font-weight: bold; }
h2 { font-size: 11.5pt; margin: 4mm 0 1mm; }
.sign { display: flex; justify-content: space-between; margin-top: 12mm; }
.sign div { width: 45%; border-top: 1px solid #1b2836; padding-top: 2mm; text-align: center; font-size: 9.5pt; }
.specimen { position: fixed; top: 42%; left: 0; right: 0; text-align: center; font-size: 64pt; font-weight: bold;
            color: rgba(214, 69, 65, .13); transform: rotate(-28deg); letter-spacing: .1em; }
.note { margin-top: 6mm; font-size: 8.5pt; color: #8a96a3; border-top: 1px dashed #c9d2dc; padding-top: 2mm; }
.card { border: 2px solid #1b2836; border-radius: 4mm; padding: 6mm; width: 140mm; margin: 8mm auto; }
.card table td:first-child { width: 42%; }
"""


def page(title: str, subtitle: str, body: str, ref: str) -> str:
    return f"""<!doctype html><html lang="ro"><head><meta charset="utf-8"><style>{STYLE}</style></head><body>
<div class="specimen">SPECIMEN</div>
<div class="head"><span>Document demonstrativ FileGrid</span><span>{ref}</span></div>
<h1>{title}</h1><p class="sub">{subtitle}</p>
{body}
<p class="note">SPECIMEN – date fictive, generate pentru demonstrarea aplicației FileGrid. Nu are valoare juridică.</p>
</body></html>"""


def rows(pairs) -> str:
    return "<table>" + "".join(f"<tr><td>{k}</td><td>{v}</td></tr>" for k, v in pairs) + "</table>"


DOCUMENTS = {
    "cerere-racordare.pdf": page(
        "Cerere de racordare", "la rețeaua electrică de distribuție – loc de consum nou",
        "<h2>1. Solicitant</h2>" + rows([
            ("Tip solicitant", "Persoană fizică"), ("Nume și prenume", APPLICANT["name"]),
            ("CNP", APPLICANT["cnp"]), ("Domiciliu", APPLICANT["address"]),
            ("Telefon", APPLICANT["phone"]), ("E-mail", APPLICANT["email"])])
        + "<h2>2. Locul de consum</h2>" + rows([
            ("Județ", PLACE["county"]), ("Localitate", PLACE["locality"]), ("Adresă", PLACE["address"]),
            ("Tipul cererii", "Loc de consum nou"), ("Putere solicitată", "9,5 kW (monofazat)"),
            ("Destinație", "Locuință individuală")])
        + "<h2>3. Documente anexate</h2><ul><li>Copie act de identitate</li>"
          f"<li>Aviz de amplasament nr. {PERMIT['aa']} din {PERMIT['issued']}</li></ul>"
        + "<p>Declar pe propria răspundere că datele de mai sus sunt reale și sunt de acord cu prelucrarea lor "
          "în scopul soluționării cererii, conform Politicii de confidențialitate.</p>"
        + f"<div class='sign'><div>Data: {PERMIT['issued']}</div><div>Semnătura solicitantului</div></div>",
        "Formular CR-01"),
    "act-identitate.pdf": page(
        "Copie act de identitate", "carte de identitate – persoană fizică",
        "<div class='card'>" + rows([
            ("Nume", "DEMO"), ("Prenume", "ION"), ("CNP", APPLICANT["cnp"]), ("Seria și nr.", "XX 000000"),
            ("Data nașterii", "29.05.1996"), ("Domiciliu", APPLICANT["address"]),
            ("Emis de", "SPCLEP (specimen)"), ("Valabil până la", "29.05.2031")]) + "</div>"
        + "<p style='text-align:center'>Conform cu originalul.</p>"
        + "<div class='sign'><div>Data: 01.11.2026</div><div>Semnătura titularului</div></div>",
        "Copie CI"),
    "certificat-inregistrare.pdf": page(
        "Certificat de înregistrare", "persoană juridică – pentru solicitanți firme",
        rows([("Denumire", COMPANY["name"]), ("Cod unic de înregistrare", COMPANY["cui"]),
              ("Nr. Registrul Comerțului", COMPANY["reg"]), ("Sediu social", COMPANY["address"]),
              ("Data înregistrării", "15.03.2020"), ("Emis de", "Oficiul Registrului Comerțului (specimen)")])
        + "<div class='sign'><div>Data: 01.11.2026</div><div>Semnătură și ștampilă</div></div>",
        "Certificat ONRC"),
    "aviz-amplasament.pdf": page(
        "Aviz de amplasament", f"nr. {PERMIT['aa']} din {PERMIT['issued']}",
        rows([("Beneficiar", APPLICANT["name"]),
              ("Amplasament", f"{PLACE['address']}, {PLACE['locality']}, jud. {PLACE['county']}"),
              ("Obiectiv", "Locuință individuală P+1"), ("Putere aprobată", "9,5 kW"),
              ("Emitent", "Operatorul de distribuție – Sucursala Ilfov (specimen)"),
              ("Data emiterii", PERMIT["issued"]), ("Valabil până la", PERMIT["valid"])])
        + "<h2>Condiții</h2><p>Amplasamentul este avizat favorabil, cu respectarea distanțelor de siguranță față de "
          "rețelele electrice existente. Racordarea se realizează prin branșament monofazat subteran.</p>"
        + "<div class='sign'><div>Întocmit</div><div>Șef sucursală</div></div>",
        "Aviz AA"),
    "certificat-urbanism.pdf": page(
        "Certificat de urbanism", f"nr. {PERMIT['cu']} din {PERMIT['issued']}",
        rows([("Solicitant", APPLICANT["name"]),
              ("Imobil", f"{PLACE['address']}, {PLACE['locality']}, jud. {PLACE['county']}"),
              ("Scopul", "Construire locuință și racordare la utilități"),
              ("Emitent", "Autoritatea locală Voluntari (specimen)"),
              ("Data emiterii", PERMIT["issued"]), ("Valabil până la", PERMIT["valid"])])
        + "<h2>Regimul tehnic</h2><p>Terenul este situat în intravilan, zonă rezidențială. Se pot executa lucrări de "
          "racordare la rețeaua de energie electrică în condițiile avizului operatorului de distribuție.</p>"
        + "<div class='sign'><div>Arhitect-șef</div><div>Primar</div></div>",
        "Certificat CU"),
}


DOCUMENTS["cerere-racordare-firma.pdf"] = page(
    "Cerere de racordare", "la rețeaua electrică de distribuție – spor de putere",
    "<h2>1. Solicitant</h2>" + rows([
        ("Tip solicitant", "Persoană juridică"), ("Denumire", COMPANY["name"]),
        ("CUI", COMPANY["cui"]), ("Sediu", COMPANY["address"]),
        ("Telefon", "+40 731 555 010"), ("E-mail", "office@demo-energie.example.ro")])
    + "<h2>2. Locul de consum</h2>" + rows([
        ("Județ", "Ilfov"), ("Localitate", "Voluntari"), ("Adresă", "Bd. Demonstrației nr. 10, hala 2"),
        ("Tipul cererii", "Spor de putere"), ("Putere solicitată", "45 kW (trifazat)"),
        ("Destinație", "Atelier de producție")])
    + "<h2>3. Documente anexate</h2><ul><li>Certificat de înregistrare ONRC</li>"
      f"<li>Certificat de urbanism nr. {PERMIT['cu']} din {PERMIT['issued']}</li></ul>"
    + "<p>Declar pe propria răspundere că datele de mai sus sunt reale.</p>"
    + f"<div class='sign'><div>Data: {PERMIT['issued']}</div><div>Semnătură și ștampilă</div></div>",
    "Formular CR-01")

FAULT_DOCUMENTS = {
    # Same event as avarie-voluntari.json: 15:30 UTC is 17:30 in Romania (EET).
    "avarie-voluntari.pdf": page(
        "Raport de avarie", "fișa evenimentului în rețeaua de distribuție",
        "<h2>1. Localizare</h2>" + rows([
            ("Regiune", "Muntenia"), ("Județ", "Ilfov"), ("Localitate", "Voluntari"),
            ("Cod stație", "ST-MUN-VOLUNTARI-110")])
        + "<h2>2. Eveniment</h2>" + rows([
            ("Momentul avariei", "14.11.2026 17:30"), ("Alimentare restabilită", "–"),
            ("Severitate", "Ridicată"), ("Clienți afectați", "1.240"), ("Cauză", "Cablu subteran deteriorat"),
            ("Descriere", "Declanșare protecție pe fiderul 20 kV Voluntari Nord. Echipă trimisă la fața locului.")])
        + "<h2>3. Măsurători la declanșare</h2><table><tr><td>17:29:58</td><td>20,1 kV · 312 A</td></tr>"
          "<tr><td>17:30:00</td><td>0,0 kV · 0 A</td></tr></table>"
        + "<div class='sign'><div>Dispecer de serviciu</div><div>Șef tură</div></div>",
        "Fișa FA-2026-1114"),
    "avarie-constanta.pdf": page(
        "Raport de avarie", "fișa evenimentului în rețeaua de distribuție",
        "<h2>1. Localizare</h2>" + rows([
            ("Regiune", "Dobrogea"), ("Județ", "Constanța"), ("Localitate", "Mangalia"),
            ("Cod stație", "ST-DOB-MANGALIA-20")])
        + "<h2>2. Eveniment</h2>" + rows([
            ("Momentul avariei", "12.11.2026 03:05"), ("Alimentare restabilită", "12.11.2026 06:40"),
            ("Severitate", "Medie"), ("Clienți afectați", "310"), ("Cauză", "Vânt puternic, conductor rupt"),
            ("Descriere", "Conductor rupt pe LEA 20 kV Mangalia Sud între stâlpii 14 și 15. Alimentare refăcută prin buclare.")])
        + "<div class='sign'><div>Dispecer de serviciu</div><div>Șef tură</div></div>",
        "Fișa FA-2026-1112"),
}


def scan(pdf: Path, target: Path) -> None:
    """Renders the first page and saves it as an image-only PDF that looks scanned: no text layer."""
    subprocess.run(["pdftoppm", "-r", "150", "-gray", "-png", "-singlefile", str(pdf), str(pdf.with_suffix(""))],
                   check=True)
    image = Image.open(pdf.with_suffix(".png")).convert("L")
    image = image.rotate(0.7, resample=Image.BICUBIC, expand=True, fillcolor=255).filter(ImageFilter.GaussianBlur(0.6))
    noise = Image.effect_noise(image.size, 18)
    image = Image.blend(image, noise, 0.08)
    image.save(target, "PDF", resolution=150)


def main() -> None:
    browser = shutil.which("chromium") or shutil.which("google-chrome") or shutil.which("chromium-browser")
    if not browser:
        sys.exit("chromium or google-chrome is required")
    OUT.mkdir(parents=True, exist_ok=True)
    # Snap-packaged Chromium can only read and write under $HOME, so the scratch files live there.
    with tempfile.TemporaryDirectory(dir=Path.home()) as tmp:
        jobs = [(name, html, OUT) for name, html in DOCUMENTS.items()]
        jobs += [(name, html, FAULTS) for name, html in FAULT_DOCUMENTS.items()]
        for name, html, out in jobs:
            source = Path(tmp) / (name + ".html")
            source.write_text(html, encoding="utf-8")
            target = Path(tmp) / name
            subprocess.run([browser, "--headless=new", "--disable-gpu", "--no-pdf-header-footer",
                            f"--print-to-pdf={target}", source.as_uri()],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
            shutil.copy(target, out / name)
            print(f"wrote {(out / name).relative_to(ROOT)}")
            if name in SCANNED:
                scanned = out / name.replace(".pdf", "-scanata.pdf")
                scan(target, scanned)
                print(f"wrote {scanned.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
