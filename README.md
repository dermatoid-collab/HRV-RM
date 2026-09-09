# HRV-RM

App Android nativa (Kotlin + Jetpack Compose, interfaccia in inglese, tema dark-only)
per misurare l'HRV tramite camera-PPG (dito su obiettivo + flash della fotocamera
posteriore), calcolare un HRV Score personale e caricare il risultato su
[Intervals.icu](https://intervals.icu).

## Come funziona

1. **Acquisizione (`ppg/`)** — `PpgCameraSource` accende il flash e usa CameraX
   (`ImageAnalysis`, output RGBA) per campionare il **canale rosso** medio di ogni
   frame: sotto flash, con dito in occlusione, il rosso porta il segnale pulsatile più
   pulito (il canale luma di default mescola verde/blu, che in quelle condizioni sono
   quasi solo rumore). Una volta finita la fase di stabilizzazione, l'esposizione e il
   bilanciamento del bianco vengono **bloccati** (`lockExposure()`, via Camera2Interop)
   — l'auto-esposizione altrimenti "combatte" attivamente il segnale, rinormalizzando
   di continuo la luminosità e sopprimendo l'ampiezza che si sta cercando di misurare.
2. **Elaborazione (`ppg/PpgSignalProcessor`)** — detrend, smoothing, rilevazione dei
   picchi con periodo refrattario e scarto degli artefatti (battiti fisiologicamente
   implausibili o troppo distanti dal ritmo locale) producono una serie di intervalli
   RR "puliti".
3. **Metriche HRV (`hrv/`)** — RMSSD, SDNN, pNN50, frequenza media da letteratura
   standard. L'**HRV Score** ricalca la metodologia pubblicata da HRV4Training (Altini,
   "Daily score, baseline and normal range: an overview") invece di uno z-score
   sul singolo giorno:
   - il valore confrontato con la baseline è una **media mobile delle ultime 7
     misurazioni** di ln(RMSSD), non la lettura grezza del giorno;
   - la **banda "normale"** è stretta, ±0,5 deviazioni standard giorno-su-giorno
     calcolate sulle ultime 60 misurazioni (la "smallest worthwhile change" della
     letteratura sportiva), non ±2,5 SD;
   - oltre allo **score continuo 0–100** (utile per un grafico di trend), l'app espone
     un flag qualitativo `withinNormalRange` (nella norma / fuori norma) — il segnale
     che dovrebbe davvero guidare le decisioni, non il numero in sé;
   - viene mostrato anche un valore in **scala "stile HRV4Training"** (`altiniScaleValue`
     = 2×ln(RMSSD) mediato, tipicamente 6–10 per un adulto) insieme ai confini della
     banda nella stessa scala — utile per chi è abituato a leggere quei numeri, anche
     se è puramente un fattore di scala cosmetico (uno z-score non cambia).
   - servono almeno 7 misurazioni precedenti prima che score/banda siano disponibili.
   Non è una riproduzione dell'algoritmo proprietario di HRV4Training (chiuso), ma la
   stessa logica metodologica, implementata da zero.
4. **Storico locale (`data/`)** — Room + DataStore per misurazioni e credenziali.
5. **Upload (`network/`)** — client Retrofit con Basic Auth verso l'API REST di
   Intervals.icu (`PUT /api/v1/athlete/{id}/wellness/{date}`, campi `hrv`/`hrvSDNN`/
   `restingHR` e il campo custom `HRVRM` con il punteggio 0–100; quest'ultimo viene
   omesso finché la baseline personale non è pronta, cioè per le prime 7 misurazioni).
   Nella tab Settings, il pulsante **"Test connection"** fa una chiamata di sola
   lettura (`GET /api/v1/athlete/{id}/events`, stesso endpoint collaudato usato da
   ERG-RM per leggere il calendario) per verificare che API key e Athlete ID
   corrispondano *prima* di fare una misurazione intera — utile perché una API key
   di intervals.icu è valida solo per l'atleta che l'ha generata: un Athlete ID
   sbagliato dà lo stesso identico `403 Access denied` di una key sbagliata.
   L'Athlete ID viene salvato esattamente come digitato (solo trim, nessun prefisso
   `i` aggiunto automaticamente) — vanno inseriti entrambi così come compaiono sul
   proprio account intervals.icu.

## Interfaccia

- **Sempre dark**, nessuna variante chiara (`ui/theme/Theme.kt`).
- **Solo in inglese** — nessuna stringa localizzata in italiano nell'app.
- Grafico PPG live con **scroll continuo** (refresh ogni 100ms, non 1s) e linea spessa,
  disegnato sul segnale già filtrato (detrend + smoothing), non sul dato grezzo.
- Lo schermo resta acceso per tutta la misurazione (`View.keepScreenOn`): altrimenti,
  andando in timeout, l'activity va in pausa e CameraX chiude la sessione — spegnendo
  il flash a metà lettura.

## Setup

1. Apri il progetto in Android Studio (Koala o successivo).
2. Su [intervals.icu](https://intervals.icu) vai in *Settings → Developer Settings*
   per generare l'API Key e trovare il tuo Athlete ID (es. `i123456`). Se vuoi anche
   il campo HRV Score, crea un custom wellness field chiamato **`HRVRM`**.
3. Nell'app, tab **Settings**, inserisci API Key e Athlete ID.
4. Tab **Measure** → tieni fermi dito, camera e flash per ~65 secondi (5s di
   stabilizzazione + 60s di misurazione).

Requisiti minimi: Android 8.0 (API 26), fotocamera posteriore con flash.

## Build

Apri il progetto in Android Studio (Koala o successivo) e sincronizza, oppure da
terminale con l'Android SDK configurato:

```
./gradlew assembleDebug
```

Un workflow GitHub Actions (`.github/workflows/build-apk.yml`) compila anche un APK
debug a ogni push su questo branch (o su richiesta manuale, tab *Actions* → *Build
debug APK* → *Run workflow*) e lo carica come artifact del workflow — utile in
ambienti senza Android SDK locale.

Le build debug usano un keystore fisso versionato nel repo (`app/debug.keystore`,
riferito da `signingConfigs.debug` in `app/build.gradle.kts`) invece di quello
generato automaticamente da Android Studio/AGP. Senza questo, ogni runner CI
(macchina pulita ad ogni esecuzione) genera una firma casuale diversa, e installare
un nuovo APK sopra uno precedente con firma diversa **fallisce silenziosamente** —
Android rifiuta l'installazione senza un messaggio chiaro. Con il keystore fisso,
tutte le build (CI o locali) hanno sempre la stessa firma e si aggiornano senza
problemi. È una chiave di solo debug con password pubblica nota (`android`, lo
standard AGP) — non è mai usata per una release, non c'è nulla da proteggere.

## Nota sull'ambiente di build di questa sessione

Questo progetto è stato preparato in un ambiente cloud isolato senza Android SDK e
senza accesso a `dl.google.com` (repository Maven di Google), quindi **non è stato
possibile eseguire una build Gradle completa per verificarlo localmente**. Il codice
è stato scritto e rivisto manualmente con attenzione; la build reale avviene tramite
il workflow GitHub Actions sopra (i runner hanno SDK Android pre-installato) o in
Android Studio in locale.
