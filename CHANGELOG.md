# Changelog

Every release has its notes in English and in Italian. `scripts\release.cmd` publishes the English ones on
GitHub, after `docs/release-intro.md`. New entries go under "Unreleased"; a release moves them under its
version and date.

## Unreleased

## 0.4.0 (2026-11-22)

### English

- Phone apps in the car (with Shizuku, Android 14 or later): pick them in Settings › Apps and they open inside
  BetterAuto's screen, also with the phone locked, by touch or with the knob (turn, arrows, press). They pause while
  driving unless you allow them; apps that protect what they show (Netflix, banking) can't be shown. They sit in the
  bar after the media apps, with a phone badge; when there is no room an Apps button gathers them, and the new Apps
  screen lists every app. Maps, Waze, YouTube, Chrome, WhatsApp and Telegram are chosen until you change the list.
  Without Shizuku running (or on Android 13 and earlier) the setting says so, in the car and on the phone.
  While a phone app is open its chip stays at the top on every screen: press it to go back to the app, or its X to
  close it. Typing in a phone app brings up the phone's own keyboard inside the app, in the car.
- Holding the knob's press on a phone app opens a panel: go back to the bar, close the app, and, on cars without a
  touchscreen, use a pointer for apps that don't answer the knob (push the knob to move it, turn to scroll, press to
  tap; remembered per app). The pointer slides rather than jumps, and quick pushes the same way carry it further.
  The panel also puts the app on the whole screen, without the bar and the status bar: Back (or the panel again,
  from the small tab at the top by touch) brings them back. Settings › Apps says how apps open: only when chosen,
  as last chosen per app, or always on the whole screen.
- Route preferences in Settings › Map: fastest or shortest, and avoid tolls, motorways, ferries (under More options
  also unpaved roads, cash-only tolls and borders). They are kept off only where there is another way; changed during
  a trip, the route is worked out again.
- A new destination first shows the whole route on the map, with up to two other ways (only without stops): their
  time, length, tolls and arrival. Turn the knob or tap to choose, press or Go to start; it starts by itself as the
  car drives off, or after 10 seconds if you leave it alone (touch the map or turn the knob and it waits for Go).
  Avoid tolls, motorways or ferries right there: the routes are worked out again
  at once.
- Lanes: before a junction or an exit the maneuver card shows the lanes, the ones to take lit, where the map data
  says which (mostly motorways and big junctions); the car's cluster gets them too.
- During a trip the map's search button shows a route icon: it opens the navigation's menu (voice, edit trip, skip
  stop, end), not just a search.
- Cars that offer a second screen (an instrument cluster or a navigation-only display) get BetterAuto on the main
  screen: one listing the cluster first got the launcher drawn there.
- During a trip the next turn, its distance and road go to the car's instrument cluster as soon as the car lets
  BetterAuto be the navigation: cars that never asked for them got nothing before.
- Cars with a touchpad instead of a touchscreen can drive BetterAuto with it: slide one way and the focus goes to
  the next control that way, one after the other (also into the bar and down long lists), tap twice quickly to
  press (a single tap does nothing, so a finger laid on the pad by chance presses nothing), hold to press long. The
  pad's own buttons select and go back.
- The music in the car keeps closer to the phone: after a hiccup of the connection its delay no longer stays
  higher for the rest of the drive, so a video in a phone app stays with its sound.
- The arrival buttons no longer close as the car enters the garage: the car's GPS losing the satellites there
  made them start over.
- When a panel closes (arrival buttons and the like) the car screen no longer stays halfway through the fade,
  lighter, until the knob is moved.
- After a touch, once the knob had been used, the whole car screen no longer turns slightly lighter until the knob
  moves again (Android's own focus highlight, now off: our rings show the focus).

### Italiano

- App del telefono in auto (con Shizuku, Android 14 o successivo): sceglile in Impostazioni › App e si aprono dentro
  lo schermo di BetterAuto, anche a telefono bloccato, col tocco o con la manopola (gira, frecce, premi). Si fermano
  durante la guida, se non lo permetti; le app che proteggono quello che mostrano (Netflix, banca) non si possono
  mostrare. Stanno nella barra dopo le app musicali, con il badge del telefono; quando non c'è spazio le raccoglie
  un pulsante App, e la nuova schermata App elenca tutte le app. Maps, Waze, YouTube, Chrome, WhatsApp e Telegram
  sono già scelte finché non cambi l'elenco. Senza Shizuku attivo (o con Android 13 e precedenti) l'impostazione lo
  dice, in auto e sul telefono. Finché un'app del telefono è aperta, la sua chip resta in alto in ogni schermata:
  premila per tornare all'app, o la sua X per chiuderla. Per scrivere in un'app del telefono compare la tastiera del
  telefono dentro l'app, in auto.
- Tenendo premuta la manopola su un'app del telefono si apre un pannello: torna alla barra, chiudi l'app e, sulle auto
  senza touchscreen, usa un puntatore per le app che non rispondono alla manopola (spingi la manopola per muoverlo,
  girala per scorrere, premi per toccare; ricordato per ogni app). Il puntatore scivola invece di saltare, e più
  spinte veloci nella stessa direzione lo portano più lontano. Il pannello mette anche l'app a schermo intero, senza
  barra e barra di stato: Back (o di nuovo il pannello, col tocco dalla linguetta in alto) le riporta. In
  Impostazioni › App scegli come si aprono le app: solo quando lo scegli, come l'hai lasciata per ogni app, o sempre
  a schermo intero.
- Preferenze del percorso in Impostazioni › Mappa: più veloce o più breve, ed evita pedaggi, autostrade, traghetti
  (in Altre opzioni anche strade sterrate, pedaggi solo in contanti e confini). Vengono evitati solo dove c'è un'altra
  strada; se le cambi durante un viaggio, il percorso viene ricalcolato.
- Una nuova destinazione mostra prima il percorso intero sulla mappa, con fino a due alternative (solo senza tappe):
  tempo, lunghezza, pedaggi e arrivo di ciascuna. Gira la manopola o tocca per scegliere, premi o Vai per partire;
  parte da sola quando l'auto si muove, o dopo 10 secondi se non la tocchi (se tocchi la mappa o giri la manopola
  aspetta Vai). Lì stesso puoi evitare pedaggi, autostrade o traghetti:
  i percorsi vengono ricalcolati subito.
- Corsie: prima di un incrocio o di un'uscita la scheda della manovra mostra le corsie, evidenziando quelle da prendere,
  dove i dati della mappa lo dicono (soprattutto autostrade e grandi incroci); le riceve anche il quadro strumenti.
- Durante un viaggio il pulsante di ricerca della mappa mostra l'icona di un percorso: apre il menu della navigazione
  (voce, modifica viaggio, salta tappa, termina), non solo la ricerca.
- Le auto che offrono un secondo schermo (quadro strumenti o display solo per la navigazione) hanno BetterAuto sullo
  schermo principale: in una che elencava prima il quadro, il launcher veniva disegnato lì.
- Durante un viaggio la prossima svolta, la sua distanza e la strada arrivano al quadro strumenti dell'auto appena
  l'auto lascia la navigazione a BetterAuto: le auto che non le chiedevano prima non ricevevano nulla.
- Le auto con un touchpad al posto del touchscreen possono usarlo per BetterAuto: striscia in una direzione e il
  focus passa al controllo successivo in quella direzione, uno dopo l'altro (anche verso la barra e lungo le liste
  lunghe), tocca due volte velocemente per premere (un tocco singolo non fa nulla, così un dito appoggiato per
  sbaglio non preme niente), tieni premuto per la pressione lunga. I pulsanti del touchpad selezionano e tornano
  indietro.
- La musica in auto resta più vicina al telefono: dopo un intoppo della connessione il suo ritardo non resta più alto
  per il resto del viaggio, così un video in un'app del telefono resta allineato al suo audio.
- I pulsanti dell'arrivo non si chiudono più quando l'auto entra in garage: il GPS dell'auto, perdendo lì i
  satelliti, li faceva ripartire da capo.
- Quando un pannello si chiude (pulsanti dell'arrivo e simili) lo schermo dell'auto non resta più a metà della
  dissolvenza, più chiaro, finché non si muove la manopola.
- Dopo un tocco, se prima si era usata la manopola, lo schermo dell'auto non diventa più un po' più chiaro finché non
  si muove di nuovo la manopola (l'evidenziazione del focus di Android, ora spenta: il focus lo mostrano i nostri
  anelli).

## 0.3.6 (2026-11-18)

### English

- A wireless connection cut by the phone closing BetterAuto for memory during the drive (some Xiaomi phones,
  even with the car screen on) connects again by itself, with auto-connect on.

### Italiano

- Una connessione senza fili interrotta dal telefono che chiude BetterAuto per memoria durante la guida (alcuni
  Xiaomi, anche con lo schermo dell'auto acceso) si ricollega da sola, con la connessione automatica attiva.

## 0.3.5 (2026-11-18)

### English

- With Shizuku, the car's notifications come back on phones that don't hand them to BetterAuto again after closing
  it for memory (some Xiaomi phones): 0.3.4 asked Android in a way that did nothing. Without Shizuku, allow
  BetterAuto's Autostart on those phones.
- Spotify's cover no longer flickers in the car: it was taken as a new one at every update.

### Italiano

- Con Shizuku, le notifiche in auto tornano sui telefoni che non le ridanno a BetterAuto dopo averla chiusa per
  memoria (alcuni Xiaomi): la 0.3.4 lo chiedeva ad Android in un modo che non faceva nulla. Senza Shizuku,
  su quei telefoni consenti l'avvio automatico di BetterAuto.
- La copertina di Spotify non lampeggia più in auto: veniva presa come nuova a ogni aggiornamento.

## 0.3.4 (2026-11-18)

### English

- Phones that don't hand BetterAuto its notifications again after closing it for memory (some Xiaomi phones)
  no longer show "no notification access" in the car with access given: BetterAuto asks for them back.
- On cars with two navigation streams (a Toyota with a Harman head unit) the prompts go to the other one: on the
  first nothing was heard, even with the car granting them their moment.
- Spotify's like ("Add to your collection") is the first of its own buttons shown, before the radio.

### Italiano

- I telefoni che non ridanno le notifiche a BetterAuto dopo averla chiusa per memoria (alcuni Xiaomi) non
  mostrano più "nessun accesso alle notifiche" in auto con l'accesso dato: BetterAuto le richiede.
- Sulle auto con due canali per il navigatore (una Toyota con l'autoradio Harman) le indicazioni vanno
  sull'altro: sul primo non si sentiva nulla, anche con l'auto che dava loro il permesso.
- Il "mi piace" di Spotify ("Aggiungi alla raccolta") è il primo dei suoi pulsanti mostrati, prima della radio.

## 0.3.3 (2026-11-17)

### English

- The navigation prompts ask the car for their moment on its speakers, also over the music: a Toyota with a
  Harman head unit didn't play them otherwise.
- The car's phone key answers a ringing call, otherwise opens the phone; on cars without separate answer and
  hang-up keys (a Toyota) it also hangs up: the car closed its call screen while the call went on. Where Android
  doesn't show BetterAuto the calls (some Xiaomi phones), it still answers and hangs up, with the permission to
  answer calls.
- With nothing playing, the music card has a button to play the music app used last; a music app picked in the
  bar is started as Android Auto does, also when it was closed.
- A player's own buttons (like, radio, add to playlist...) no longer fill the row and squeeze the app's name:
  only the two that matter most in the car are shown, liking the song first.
- A chat app's paused voice message no longer takes the music card as "Unknown title" when the music app is
  closed.

### Italiano

- Le indicazioni del navigatore chiedono all'auto il loro momento sugli altoparlanti, anche sopra la musica:
  una Toyota con l'autoradio Harman altrimenti non le faceva sentire.
- Il tasto del telefono dell'auto risponde a una chiamata che squilla, altrimenti apre il telefono; sulle auto
  senza tasti separati per rispondere e riagganciare (una Toyota) riaggancia anche: l'auto chiudeva la sua
  schermata della chiamata mentre la chiamata continuava. Dove Android non fa vedere le chiamate a BetterAuto
  (alcuni telefoni Xiaomi), risponde e riaggancia lo stesso, con il permesso di rispondere alle chiamate.
- Con niente in riproduzione, il riquadro della musica ha un pulsante per far partire l'ultima app di musica
  usata; un'app scelta dalla barra viene avviata come fa Android Auto, anche se era chiusa.
- I pulsanti propri di un player (mi piace, radio, aggiungi a playlist...) non riempiono più la riga
  schiacciando il nome dell'app: si vedono solo i due che contano di più in auto, prima il "mi piace".
- Un messaggio vocale in pausa di un'app di chat non prende più il riquadro della musica come "Titolo
  sconosciuto" quando l'app di musica è chiusa.

## 0.3.2 (2026-11-17)

### English

- Cars that offer two music channels (a Toyota with a Harman head unit) now get the music on the first one:
  sent to the other, nothing was heard.
- With Shizuku, the voice commands' replies and tones are heard in the car instead of from the phone.
- After a voice command the car's volume knob goes back to the music: on a Toyota it kept changing the
  voice's volume.

### Italiano

- Le auto che offrono due canali per la musica (una Toyota con l'autoradio Harman) ora ricevono la musica sul
  primo: mandata sull'altro, non si sentiva nulla.
- Con Shizuku, le risposte e i suoni dei comandi vocali si sentono in auto invece che dal telefono.
- Dopo un comando vocale la manopola del volume dell'auto torna a regolare la musica: su una Toyota
  continuava a regolare il volume della voce.

## 0.3.0 (2026-11-16)

### English

- The home screen is yours to arrange: up to six widgets, with − and + for how many, in rows or columns, moved
  wherever you like (onto another to swap them, beside one, or on a line of their own) and resized, in the car
  with a finger or the knob, or on the phone. Landscape and portrait car screens each keep their own
  arrangement. The earlier one, two or three widget layouts start over from the default.
- Without Shizuku, a player that doesn't let its sound be copied (recent Spotify versions) now plays on the
  phone with a notification saying why, instead of being heard nowhere.
- The app no longer stops when the connection drops while the car's Bluetooth is being set up.
- The car's destinations panel closes by itself when a destination is set on the phone.
- Shizuku now works on phones where it couldn't start BetterAuto's audio service (some ColorOS phones): the
  music, Spotify included, goes to the car there too.
- With Shizuku, the music sometimes stayed on the phone until you reconnected, when the car's Bluetooth was set
  up at the same moment: now it goes to the car.

### Italiano

- La schermata Home la disponi tu: fino a sei widget, con − e + per quanti, in righe o colonne, spostati dove
  vuoi (su un altro per scambiarli, di fianco a uno, o su una riga tutta loro) e ridimensionati, in auto con il
  dito o la manopola, oppure sul telefono. Gli schermi dell'auto orizzontali e verticali hanno ciascuno la
  propria disposizione. Le vecchie disposizioni a uno, due o tre widget ripartono da quella iniziale.
- Senza Shizuku, un player che non lascia copiare il suo audio (le versioni recenti di Spotify) ora si sente
  dal telefono con una notifica che spiega perché, invece di non sentirsi da nessuna parte.
- L'app non si chiude più quando la connessione cade mentre si prepara il Bluetooth dell'auto.
- Il pannello delle destinazioni in auto si chiude da solo quando si imposta una destinazione sul telefono.
- Shizuku ora funziona anche sui telefoni dove non riusciva ad avviare il servizio audio di BetterAuto (alcuni
  telefoni ColorOS): anche lì la musica, Spotify compreso, va all'auto.
- Con Shizuku la musica a volte restava sul telefono finché non ti ricollegavi, quando il Bluetooth dell'auto si
  preparava nello stesso momento: ora va all'auto.

## 0.2.0 (2026-11-13)

### English

- A quick stop with the car switched off and on again keeps the wireless connection: the calls are connected
  again and the music plays again when you drive off.
- After the phone restarts, a notification says when Shizuku isn't running or doesn't allow BetterAuto,
  before you find out in the car.
- "Send diagnostic log" on the phone, to report a problem: what the app and the car did, with no positions,
  contacts or messages, shared with whoever you choose.
- A download less than half the size: the APK is compressed and carries only what phones run (ARM).

### Italiano

- Una sosta breve con l'auto spenta e riaccesa mantiene la connessione senza fili: le chiamate si ricollegano
  e la musica riparte quando riparti.
- Dopo il riavvio del telefono, una notifica avvisa se Shizuku non è avviato o non dà il permesso a
  BetterAuto, prima di scoprirlo in auto.
- "Invia log di diagnostica" sul telefono, per segnalare un problema: cosa hanno fatto l'app e l'auto, senza
  posizioni, contatti o messaggi, mandato a chi scegli tu.
- Un download grande meno della metà: l'APK è compresso e contiene solo ciò che serve ai telefoni (ARM).

## 0.1.0 (2026-11-11)

### English

First public build of BetterAuto, the phone side of Android Auto: its own car launcher, music, calls,
notifications, voice commands, vehicle data, smart home and a map, over USB or wirelessly.

- The phone keeps its sound if the app stops unexpectedly: the audio moved by Shizuku comes back to its
  speaker, and a dropped wireless link no longer crashes the app.
- Problems told by notification: Shizuku stopped, music staying on the phone.
- The music starts by itself on connecting, even while the player is still connecting or the car briefly
  takes the audio back.
- The phone is connected to the car for calls, with retries when the car is slow to answer.
- The progress bar stays readable on grey covers and at night.

### Italiano

Prima versione pubblica di BetterAuto, il lato telefono di Android Auto: il suo launcher per l'auto, musica,
chiamate, notifiche, comandi vocali, dati del veicolo, casa intelligente e una mappa, via USB o senza fili.

- Il telefono non perde l'audio se l'app si chiude all'improvviso: l'audio spostato da Shizuku torna al suo
  altoparlante, e un collegamento senza fili che cade non fa più chiudere l'app.
- I problemi arrivano per notifica: Shizuku fermo, musica rimasta sul telefono.
- La musica parte da sola alla connessione, anche mentre il lettore si sta ancora collegando o l'auto si
  riprende l'audio per un attimo.
- Il telefono viene collegato all'auto per le chiamate, con nuovi tentativi quando l'auto risponde lenta.
- La barra di avanzamento resta leggibile sulle copertine grigie e di notte.
