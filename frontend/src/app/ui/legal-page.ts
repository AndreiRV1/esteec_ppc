import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * The operator's legal identity. FILL IN before going live: these values are shown in the
 * privacy policy, terms and footer. They are placeholders, not real contact details.
 */
export const OPERATOR = {
  name: '[Denumirea operatorului de distribuție]',
  registration: '[CUI / Nr. Registrul Comerțului]',
  address: '[Adresa sediului social]',
  email: 'contact@example.ro',
  dpoEmail: 'dpo@example.ro',
  phone: '[Telefon]',
};

export type LegalDoc = 'privacy' | 'terms' | 'cookies';

/** Privacy policy, terms of use and cookie policy. Public: readable without an account. */
@Component({
  imports: [RouterLink],
  template: `
    <article class="page legal">
      @switch (doc()) {
        @case ('privacy') {
          <h1>Politica de confidențialitate</h1>
          <p class="muted">Ultima actualizare: 14 noiembrie 2026</p>

          <h2>Cine prelucrează datele</h2>
          <p>
            Operatorul datelor este {{ operator.name }}, {{ operator.registration }}, cu sediul în {{ operator.address }}.
            Responsabilul cu protecția datelor (DPO) poate fi contactat la
            <a [href]="'mailto:' + operator.dpoEmail">{{ operator.dpoEmail }}</a>.
          </p>

          <h2>Ce date prelucrăm și de ce</h2>
          <ul>
            <li>
              <strong>Cereri de racordare:</strong> nume, CNP sau CUI, e-mail, telefon, adresa locului de consum și documentele
              atașate (cerere, act de identitate, aviz). Le folosim pentru a analiza și soluționa cererea (art. 6 alin. 1 lit. b
              și c GDPR: demersuri precontractuale și obligații legale ale operatorului de distribuție).
            </li>
            <li>
              <strong>Citirile contorului:</strong> consumul de energie pe intervale, tensiunea și indexul, asociate locului de
              consum. Le folosim pentru facturare și pentru a-ți arăta consumul (art. 6 alin. 1 lit. b și c GDPR).
            </li>
            <li>
              <strong>Contul tău:</strong> ID-ul de client și jurnalele de autentificare, pentru securitatea accesului
              (art. 6 alin. 1 lit. f GDPR: interesul legitim de a proteja conturile).
            </li>
            <li>
              <strong>Avarii și inspecții:</strong> date tehnice despre rețea; îți arătăm avariile din zona ta.
            </li>
          </ul>

          <h2>Cum le protejăm</h2>
          <ul>
            <li>Documentele cererii sunt arhivate și criptate (AES-256-GCM, cheie separată pentru fiecare arhivă).</li>
            <li>CNP-ul/CUI-ul nu este stocat în clar: păstrăm doar o amprentă HMAC, pentru regăsirea cererilor.</li>
            <li>Integritatea fiecărui fișier este verificată (SHA-256) la fiecare citire.</li>
            <li>Toate conexiunile sunt criptate (TLS), iar accesul angajaților este limitat pe roluri și jurnalizat.</li>
          </ul>

          <h2>Cât timp le păstrăm</h2>
          <p>
            Documentele cererilor de racordare și citirile folosite la facturare se păstrează pe durata contractului și apoi
            atât cât cer obligațiile legale (de exemplu, legislația financiar-contabilă). Jurnalele de securitate se păstrează
            cel mult 12 luni.
          </p>

          <h2>Cui le transmitem</h2>
          <p>
            Datele sunt accesibile doar personalului autorizat al operatorului și furnizorilor care găzduiesc sistemul, pe baza
            unor contracte de prelucrare. Nu vindem date și nu le transferăm în afara Spațiului Economic European.
          </p>

          <h2>Drepturile tale</h2>
          <p>
            Ai dreptul de acces, rectificare, ștergere (în limitele obligațiilor legale), restricționare, portabilitate și
            opoziție. Scrie-ne la <a [href]="'mailto:' + operator.dpoEmail">{{ operator.dpoEmail }}</a>; răspundem în cel mult
            o lună. Poți depune plângere la Autoritatea Națională de Supraveghere a Prelucrării Datelor cu Caracter Personal
            (<a href="https://www.dataprotection.ro" rel="noopener" target="_blank">dataprotection.ro</a>).
          </p>
        }
        @case ('terms') {
          <h1>Termeni și condiții de utilizare</h1>
          <p class="muted">Ultima actualizare: 14 noiembrie 2026</p>

          <h2>Serviciul</h2>
          <p>
            FileGrid este platforma prin care {{ operator.name }} primește cereri de racordare, afișează clienților consumul
            măsurat și informează despre avariile din rețea.
          </p>

          <h2>Contul</h2>
          <ul>
            <li>La depunerea unei cereri de racordare primești un ID de client și o parolă. Păstrează-le confidențiale.</li>
            <li>Poți schimba parola oricând din aplicație (butonul „Schimbă parola”).</li>
            <li>Datele declarate în cerere trebuie să fie reale; documentele false pot duce la respingerea cererii.</li>
          </ul>

          <h2>Informațiile afișate</h2>
          <p>
            Consumul afișat provine din citirile contorului și are caracter informativ; factura emisă rămâne documentul de
            referință. Informațiile despre avarii se actualizează pe măsură ce echipele raportează situația din teren.
          </p>

          <h2>Utilizare permisă</h2>
          <p>
            Nu este permisă încercarea de a accesa date ale altor clienți, de a ocoli măsurile de securitate sau de a
            supraîncărca serviciul. Încercările repetate de autentificare eșuată blochează temporar contul.
          </p>

          <h2>Contact</h2>
          <p>
            {{ operator.name }}, {{ operator.address }}. E-mail:
            <a [href]="'mailto:' + operator.email">{{ operator.email }}</a>, telefon: {{ operator.phone }}.
            Pentru datele personale vezi <a routerLink="/privacy">Politica de confidențialitate</a>.
          </p>
        }
        @case ('cookies') {
          <h1>Politica privind cookie-urile</h1>
          <p class="muted">Ultima actualizare: 14 noiembrie 2026</p>
          <p>
            FileGrid folosește numai cookie-uri strict necesare pentru autentificare. Nu folosim cookie-uri de analiză, de
            publicitate sau de la terți, așa că nu îți cerem consimțământul (Legea 506/2004, art. 4 alin. 6).
          </p>
          <div class="table-wrap">
            <table>
              <thead><tr><th>Cookie</th><th>Scop</th><th>Durată</th></tr></thead>
              <tbody>
                <tr><td class="data">AUTH_SESSION_ID, KC_RESTART</td><td>Desfășurarea autentificării</td><td>Sesiune</td></tr>
                <tr><td class="data">KEYCLOAK_SESSION, KEYCLOAK_IDENTITY</td><td>Menține sesiunea de autentificare</td><td>Cel mult 10 ore</td></tr>
              </tbody>
            </table>
          </div>
          <p>
            Tokenurile de acces sunt păstrate doar în memoria paginii și dispar la închiderea ei. Poți șterge cookie-urile din
            setările browserului; vei fi rugat să te autentifici din nou.
          </p>
        }
      }
    </article>
  `,
  styles: `
    .legal { max-width: 780px; gap: 14px; }
    .legal h2 { margin-top: 12px; font-size: 1.2rem; }
    .legal ul { margin: 0; padding-left: 20px; display: grid; gap: 8px; }
  `,
})
export class LegalPage {
  readonly doc = input<LegalDoc>('privacy');
  protected readonly operator = OPERATOR;
}
