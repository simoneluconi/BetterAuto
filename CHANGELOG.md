# Changelog

Every release has its notes in English and in Italian. `scripts\release.cmd` publishes the English ones on
GitHub, after `docs/release-intro.md`. New entries go under "Unreleased"; a release moves them under its
version and date.

## Unreleased

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
