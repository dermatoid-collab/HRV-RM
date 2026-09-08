# HRV-RM

App Android nativa (Kotlin + Jetpack Compose) per misurare l'HRV tramite camera-PPG
(dito su obiettivo + flash della fotocamera posteriore), calcolare un HRV Score
personale e caricare il risultato su [Intervals.icu](https://intervals.icu).

## Come funziona

1. **Acquisizione (`ppg/`)** — `PpgCameraSource` accende il flash e usa CameraX
   (`ImageAnalysis`) per campionare la luminanza media di ogni frame: con il dito
   sopra camera e flash, il volume di sangue modula la luce che arriva al sensore,
   producendo un segnale PPG grezzo.
2. **Elaborazione (`ppg/PpgSignalProcessor`)** — detrend, smoothing, rilevazione dei
   picchi con periodo refrattario e scarto degli artefatti (battiti fisiologicamente
   implausibili o troppo distanti dal ritmo locale) producono una serie di intervalli
   RR "puliti".
3. **Metriche HRV (`hrv/`)** — RMSSD, SDNN, pNN50, frequenza media da letteratura
   standard. L'**HRV Score** (0–100) confronta il RMSSD di oggi (in scala logaritmica)
   con la baseline personale mobile (media/deviazione standard delle ultime misurazioni,
   minimo 7 per essere attendibile). Non è una riproduzione dell'algoritmo proprietario
   di alcun vendor, ma un metodo pubblicato nella letteratura sportiva (Plews/Buchheit).
4. **Storico locale (`data/`)** — Room + DataStore per misurazioni e credenziali.
5. **Upload (`network/`)** — client Retrofit con Basic Auth verso l'API REST di
   Intervals.icu (`PUT /api/v1/athlete/{id}/wellness/{date}`, campi `hrv`/`hrvSDNN`).

## Setup

1. Apri il progetto in Android Studio (Koala o successivo).
2. Su [intervals.icu](https://intervals.icu) vai in *Settings → Developer Settings*
   per generare l'API Key e trovare il tuo Athlete ID (es. `i123456`).
3. Nell'app, tab **Impostazioni**, inserisci API Key e Athlete ID.
4. Tab **Misura** → tieni fermi dito, camera e flash per ~65 secondi (5s di
   stabilizzazione + 60s di misurazione).

Requisiti minimi: Android 8.0 (API 26), fotocamera posteriore con flash.

## Nota sull'ambiente di build di questa sessione

Questo commit è stato preparato in un ambiente cloud isolato senza Android SDK e
senza accesso a `dl.google.com` (repository Maven di Google), quindi **non è stato
possibile eseguire una build Gradle completa per verificarlo**. Il codice è stato
scritto e rivisto manualmente con attenzione, ma la prima build va fatta in Android
Studio in locale: è probabile servano piccoli aggiustamenti (versioni di libreria,
`local.properties`, ecc.).
