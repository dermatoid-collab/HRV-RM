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
   picchi col metodo di Elgendi et al. 2013 ("Systolic Peak Detection in Acceleration
   Photoplethysmograms...", PLoS ONE), il rilevatore più citato in letteratura per PPG:
   segnale elevato al quadrato (solo parte positiva) per amplificare il picco sistolico
   molto più della piccola tacca dicrota, poi due medie mobili (~111ms e ~667ms) per
   isolare solo i blocchi abbastanza larghi da essere un vero battito. La tacca dicrota
   viene così esclusa dalla **forma/larghezza** del blocco, non dal solo timing (un
   periodo refrattario fisso non basta: la tacca cade prima o dopo la soglia a seconda
   della frequenza cardiaca — causa concreta di un alto tasso di battiti scartati anche
   con segnale visivamente pulito; corretto escludendo dalla scansione dei picchi la
   metà finale, meno affidabile, della finestra lunga — non le medie mobili stesse, che
   restano corrette per i punti precedenti — così un battito reale lì viene semplicemente
   rilevato un tick o due più tardi invece di generarne uno falso). Un secondo problema
   della stessa media mobile, individuato analizzando una registrazione reale esportata
   con **"Export raw data"** (vedi sotto) che scartava circa il 70% dei battiti: subito
   dopo un vero picco sistolico il segnale scende ben sotto zero per un tratto lungo
   quasi quanto la finestra di 667ms, quindi mentre la finestra *centrata* scorre oltre
   il picco la sua media può crollare verso zero proprio nella valle fra un battito e il
   successivo — a quel punto anche la piccola tacca dicrota supera la soglia (ormai
   quasi nulla anch'essa) e genera un secondo "battito" per ogni ciclo cardiaco, con
   larghezza del blocco indistinguibile da un picco vero (quindi non escluso dal solo
   controllo di larghezza). Corretto aggiungendo un **filtro di ampiezza**: un blocco
   conta come battito solo se il suo picco è almeno il 35% di una media esponenziale dei
   picchi accettati di recente — la tacca dicrota, per costruzione, è sempre molto più
   piccola di un vero picco sistolico, verificato sia sulla registrazione reale (tutti i
   blocchi "tacca" esclusi, tutti i battiti veri mantenuti) sia su un segnale sintetico
   pulito (nessun cambiamento). Segue lo scarto degli artefatti (battiti fisiologicamente
   implausibili o il cui intervallo si scosta troppo dal **ritmo recente smorzato in modo
   esponenziale** di questa persona, non da una percentuale fissa uguale per tutti). Il
   riferimento è passato per due forme sbagliate in direzioni opposte prima di arrivare a
   questa: una mediana su finestra di 5 battiti resta indietro durante un trend reale e
   sostenuto (es. l'aritmia respiratoria sinusale, il battito che rallenta gradualmente
   durante l'espirazione), scartando battiti che proseguivano benissimo il trend;
   confrontare col solo battito precedente (senza alcuno smorzamento) lasciava che un
   singolo battito rumoroso diventasse l'unico riferimento per il confronto successivo,
   innescando una cascata di scarti — peggio, non meglio, in pratica. Una media
   esponenziale smorzata (stessa tecnica già usata per la scala del grafico d'onda)
   insegue un trend reale in un paio di battiti pur restando una media della storia
   recente, non un singolo campione grezzo — un battito rumoroso la sposta solo in
   parte. La soglia resta comunque proporzionata alla variabilità recente battito-battito
   di questa persona (deviazione assoluta mediana dei passi recenti), non una percentuale
   fissa: la letteratura sull'artifact correction in HRV (Lipponen & Tarvainen 2019; gli
   scritti dello stesso Altini su PPG) segnala che soglie fisse al 20-30% scartano troppo
   per chi ha HRV genuinamente alta — tipicamente atleti — dove ampie oscillazioni
   battito-battito sono normale fisiologia, non rumore: scartarle abbassa artificialmente
   l'RMSSD calcolato, che è per definizione una misura di quelle stesse oscillazioni. Il
   **pavimento** sotto quella soglia (sotto il quale non si scende mai, anche quando la
   variabilità recente è quasi nulla) era inizialmente un valore fisso in ms — sbagliato
   per lo stesso motivo delle soglie fisse: lo stesso scarto in ms pesa molto di più, in
   percentuale, a frequenza cardiaca bassa (intervalli lunghi) che alta. Corretto in una
   **frazione del ritmo corrente** (25%), calibrata sulla stessa registrazione reale una
   volta tolti i falsi battiti della tacca dicrota: gli scarti battito-battito genuini
   risultavano ~4% del ritmo alla mediana, ~12% al 90° percentile — con la doppia
   correzione, quella registrazione (70% scartati in origine) scende all'11%, dentro il
   range considerato accettabile in letteratura (5-14%), con una frequenza cardiaca
   finale coerente e un andamento fluido, compatibile con una normale aritmia
   respiratoria sinusale. Restava un'ultima causa di scarto, individuata dall'utente
   stesso osservando il log live dei battiti: gli scarti si accumulavano quando il
   battito **rallentava** (RR che si allunga, il lato espiratorio dell'aritmia
   respiratoria sinusale), non quando accelerava. La letteratura sulla "heart rate
   asymmetry" conferma che le decelerazioni sono tipicamente più ripide e sostenute
   delle accelerazioni (il vago reagisce rapidissimo, il suo ritiro è invece lento) —
   quindi un vero rallentamento dura spesso diversi battiti di fila. Il riferimento
   smorzato però si aggiorna **solo sui battiti accettati**: se il primo battito di un
   rallentamento reale viene scartato come salto troppo grande, il riferimento resta
   congelato lì, il battito successivo dello stesso trend sembra un salto ancora più
   grande dallo stesso riferimento ormai vecchio, e così via — un blocco che si
   autoalimenta finché il trend non torna per caso vicino al valore congelato. La
   registrazione esportata mostrava esattamente questa firma: gruppi di 2-6 scarti
   consecutivi con lo *stesso identico* riferimento, tutti su battiti in allungamento,
   tutti parte dello stesso rallentamento reale e fluido. Corretto facendo sì che, dopo
   3 scarti consecutivi, il riferimento si "riallinei" alla mediana degli ultimi 5
   intervalli grezzi (non filtrati): un trend reale ha diversi valori grezzi coerenti fra
   loro su cui la mediana può agganciarsi, mentre un vero artefatto isolato (senza vicini
   coerenti) non la sposta di molto. Verificato sulla registrazione che ha rivelato il
   problema (13 scarti su 66 battiti → 8) e su una precedente (6 su 57 → 3), senza
   alcun effetto sul segnale sintetico pulito usato come controllo di regressione.
   Deliberatamente **non** applicato agli accorciamenti (RR che si accorcia): entrambe le
   registrazioni mostrano catene di scarti in allungamento fino a 6 di fila ma **zero**
   scarti consecutivi in accorciamento — nessuna prova reale che serva anche lì, e un
   vero artefatto da movimento del dito produce più facilmente battiti spuri *brevi* che
   lunghi, quindi riallinearsi su una catena di accorciamenti rischierebbe di adottare
   l'artefatto come nuovo normale invece di continuare (giustamente) a scartarlo.
3. **Metriche HRV (`hrv/`)** — RMSSD, SDNN, Mean RR, pNN50, frequenza media da
   letteratura standard, più due metriche aggiunte confrontando l'app con **Kubios HRV**
   (altra app di riferimento nel settore):
   - **Poincaré SD1/SD2** — non richiedono nuovi calcoli: sono formule chiuse su RMSSD e
     SDNN già disponibili (`SD1 = RMSSD/√2`, `SD2 = √(2·SDNN² − SD1²)`);
   - **Stress Index di Baevsky** (`hrv/HrvMetrics.kt`, `stressIndex()`) — dalla
     letteratura di medicina spaziale/cardiovascolare (Baevsky & Chernikova): istogramma
     degli RR puliti (bin da 50ms), poi `SI = AMo / (2 × Mo × MxDMn)` dove Mo è il centro
     del bin più popolato (la "moda", in secondi), AMo la percentuale di battiti in quel
     bin, MxDMn l'escursione RR max−min (in secondi). Un valore alto indica una
     distribuzione stretta e piccata (poca variabilità), uno basso una distribuzione
     larga. I dettagli di binning/stima della moda variano da implementazione a
     implementazione, quindi il numero non è detto coincida esattamente con quello di
     un'altra app, solo la stessa scala generale.

   Restano **fuori scope** per ora, confrontati con Kubios: PNS/SNS index e Physiological
   age (indici proprietari confrontati con un database normativo di popolazione che non
   abbiamo — replicabili solo come "versione nostra" tarata sulla baseline personale, non
   comparabile numericamente); Readiness % (quasi certamente combina HRV con sonno e
   carico di allenamento, dati che l'app non raccoglie); Respiratory rate, LF/HF power
   (richiedono un modulo di analisi spettrale nuovo — interpolazione della serie RR più
   stima della densità spettrale — e le linee guida standard raccomandano almeno 2-5
   minuti di registrazione per una stima affidabile della componente LF, contro i 60
   secondi del protocollo attuale).

   L'**HRV Score** ricalca la metodologia pubblicata da HRV4Training (Altini,
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
     se è puramente un fattore di scala cosmetico (uno z-score non cambia). Un post di
     forum sosteneva un moltiplicatore 20× (range 0–100), ma i valori osservati
     nell'app reale di HRV4Training (sempre attorno a 9) confermano 2×, non 20×.
   - **HRV normalizzata** (`normalizedHrvPercent` = RMSSD/meanRR × 100, in `hrv/HrvMetrics`):
     RMSSD è strutturalmente più basso a frequenze cardiache più alte (RR più corti
     lasciano meno spazio alla variabilità), quindi normalizzare per l'intervallo medio
     rende il dato più confrontabile tra misurazioni fatte a frequenze diverse. Mostrata
     nella schermata risultato, non ancora caricata su Intervals.icu.
   - servono almeno 3 misurazioni precedenti prima che score/banda siano disponibili
     (`HrvScoreCalculator.MIN_BASELINE_SAMPLES`) — soglia bassa apposta per vedere un
     valore reale presto, a costo di più rumore statistico nei primi giorni; sale in
     affidabilità man mano che si arriva verso le 60 misurazioni della finestra mobile.
   Non è una riproduzione dell'algoritmo proprietario di HRV4Training (chiuso), ma la
   stessa logica metodologica, implementata da zero.
4. **Storico locale (`data/`)** — Room + DataStore per misurazioni e credenziali. La tab
   **History** mostra, fissato sopra la lista delle misurazioni, un grafico
   dell'andamento giornaliero (`ui/history/HrvTrendChart.kt`) con la fascia della
   "normal range" personale e un marker colorato per stato (nella norma / fuori norma
   / baseline in costruzione) — stessa semantica di colore di `ScoreBadge`. Un giorno
   con più misurazioni conta solo l'ultima **nel grafico e su Intervals.icu** (che
   sovrascrive per data). La baseline dell'HRV Score no: `getPriorMeasurements` prende
   le ultime N righe per timestamp senza deduplicare per giorno, quindi una seconda
   misurazione lo stesso giorno pesa come un giorno a sé nelle finestre mobili (7
   letture per lo smoothing, 60 per la baseline) — misurare più volte in un giorno
   rende quella giornata più "pesante" nel calcolo statistico. Zoom e navigazione sono
   a gesti (pinch/trascinamento, doppio tap per resettare) più scorciatoie rapide
   7/30/90 giorni/Tutto; il toggle fra scala ln (default, coerente con `HRVRM`) e
   score 0–100 compare solo quando lo score è già disponibile.
5. **Upload (`network/`)** — client Retrofit con Basic Auth verso l'API REST di
   Intervals.icu (`PUT /api/v1/athlete/{id}/wellness/{date}`). Scriviamo **solo** il
   campo custom `HRVRM`, col valore in **scala ln stile HRV4Training**
   (`altiniScaleValue`, es. 9.1 — non lo score 0–100 comparativo: quello richiede uno
   storico, il valore in scala ln invece è sempre disponibile, fin dalla primissima
   misurazione). Deliberatamente **non** scriviamo `hrv`/`hrvSDNN`/`restingHR`: quei
   campi restano di competenza di altre integrazioni HRV (o inserimento manuale)
   sull'account Intervals.icu, che questa app non deve sovrascrivere — essendo un
   `PUT` parziale, un campo omesso nel body resta quello già presente per quel giorno.
   Nella tab Settings, il pulsante **"Test connection"** fa una chiamata di sola
   lettura (`GET /api/v1/athlete/{id}/events`, stesso endpoint collaudato usato da
   ERG-RM per leggere il calendario) per verificare che API key e Athlete ID
   corrispondano *prima* di fare una misurazione intera — utile perché una API key
   di intervals.icu è valida solo per l'atleta che l'ha generata: un Athlete ID
   sbagliato dà lo stesso identico `403 Access denied` di una key sbagliata.
   L'Athlete ID viene salvato esattamente come digitato (solo trim, nessun prefisso
   `i` aggiunto automaticamente) — vanno inseriti entrambi così come compaiono sul
   proprio account intervals.icu.
6. **Backup (`data/MeasurementBackup.kt`)** — lo storico locale vive solo sul database
   Room del dispositivo: se un aggiornamento non può installarsi sopra la build
   precedente (cambio di firma, o uno schema che richiede una migrazione distruttiva)
   l'unica via resta disinstallare e reinstallare, che cancella il database. Nella tab
   Settings, **"Export backup"** condivide (share sheet, stesso `FileProvider` già usato
   per l'export dei dati grezzi) un file **`hrv_rm_backup_ddMMyy_HHmm.json.gz`**
   (compresso in gzip) con **tutte** le misurazioni salvate — i dati già elaborati di
   ciascuna (punteggi, metriche, serie RR pulita), non i campioni grezzi della camera,
   che non vengono mai salvati su Room — **più API key e Athlete ID di Intervals.icu**,
   così un ripristino non richiede di reinserirli a mano. Questo significa che il file
   esportato contiene una credenziale vera in chiaro (compressa, non cifrata): va
   trattato come una password, perché chiunque lo intercetti (email, cloud, chat
   condivisa per errore) ottiene accesso in scrittura al wellness log di quell'atleta —
   scelta deliberata per comodità di ripristino, non una svista. **"Import backup"** apre
   il selettore di file di sistema (riconosce sia il nuovo formato gzip sia i vecchi
   backup non compressi, leggendo i byte magici invece di fidarsi dell'estensione) e
   reinserisce le misurazioni del file scelto, saltando quelle il cui timestamp esiste
   già in locale — reimportare lo stesso file, o importare su un dispositivo non del
   tutto vuoto, non duplica righe. Le misurazioni importate mantengono lo score/baseline
   calcolati al momento della misurazione originale (non vengono ricalcolati); le
   credenziali di Intervals.icu, se presenti nel backup, sovrascrivono quelle già
   salvate sul dispositivo. Un promemoria settimanale (`backup/
   BackupReminderWorker.kt`, `WorkManager` periodico) mostra una notifica che apre
   l'app direttamente sulla tab Settings — **non esporta mai da solo**, serve solo a
   non dimenticarsene; richiede il permesso di notifica su Android 13+, richiesto
   automaticamente al primo avvio.

## Metodologia di tuning di `PpgSignalProcessor`

Tarare le costanti di `PpgSignalProcessor` (soglia di Elgendi, moltiplicatore MAD, ecc.)
tramite il solo ciclo build APK → test sul telefono → resoconto a voce/video era troppo
lento e troppo "alla cieca": ogni tentativo costava minuti e restituiva solo un conteggio
finale (validi/scartati), non il segnale con cui capire *perché*. Per rompere questo ciclo,
il **Result screen** ha un pulsante **"Export raw data"** che condivide (via share sheet,
`FileProvider`) un file JSON con i campioni grezzi (`timestampMs`/`intensity`) dell'ultima
misurazione appena fatta — nient'altro, non tocca lo storico salvato. Quel file può essere
rigirato a `PpgSignalProcessor` così com'è, ma eseguito **localmente** invece che sul
telefono: `dev-tools/ppg_processor.py` è una trascrizione fedele (mantenuta manualmente
in corrispondenza 1:1 con l'implementazione Kotlin, non generata da un compilatore comune
ai due — non c'è un compilatore Kotlin in questo ambiente di sviluppo) dello stesso
algoritmo, con ogni costante tarabile da riga di comando e una modalità `--sweep` che
prova una griglia di combinazioni sulla stessa registrazione reale in pochi secondi. Un
cambiamento si porta in `PpgSignalProcessor.kt` solo dopo essere stato verificato così,
non più per tentativi sul dispositivo. Prima verifica reale del metodo: una registrazione
che scartava il 70% dei battiti ha mostrato, ispezionando il segnale filtrato campione
per campione, che non era affatto rumore — era la tacca dicrota rilevata come battito a
sé stante circa una volta su due (vedi punto 2 sopra); la stessa registrazione, con la
causa vera corretta, scarta l'11%.

## Interfaccia

- **Sempre dark**, nessuna variante chiara (`ui/theme/Theme.kt`).
- **Solo in inglese** — nessuna stringa localizzata in italiano nell'app.
- Grafico PPG live con **scroll continuo** (refresh ogni 100ms, non 1s) e linea spessa,
  disegnato sul segnale già filtrato (detrend + smoothing), non sul dato grezzo.
- Sotto l'anello di misurazione, un **log live dei battiti** (3 righe, scorrevole) mostra
  in tempo reale gli stessi intervalli RR usati per calcolare RMSSD/score — tempo
  trascorso, RR in ms, bpm istantaneo — con i battiti scartati come artefatto evidenziati
  in rosso invece che nascosti nel solo conteggio finale, ed etichettati con il motivo
  esatto dello scarto (`out of range` = fuori dal range di frequenza cardiaca plausibile,
  `irregular` = troppo distante dal ritmo recente) invece di un generico "discarded" —
  utile per capire da dove viene un tasso di scarto anomalo invece di doverlo indovinare.
- Nella schermata **Result**, il pulsante **"Export raw data"** condivide i campioni
  grezzi dell'ultima misurazione (vedi sezione "Metodologia di tuning" sopra) — utile per
  analisi offline, non per un uso quotidiano.
- Toccando una misurazione in **History** si apre il dettaglio completo
  (`ui/history/MeasurementDetailScreen.kt`) con lo stesso riepilogo della schermata
  risultato (score, metriche, stato upload con retry) per quella misurazione specifica.
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
