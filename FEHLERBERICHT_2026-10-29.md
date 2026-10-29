# Fehlerbericht LogicSim 3.5 (Stand Commit 87298fb, 29.10.2026)

Gefunden beim Test der Fortbildungsmaterialien „Vom Gatter zum Addierer“ (Release `LogicSim-3.5-20261029.zip`, Java 21, Linux/Xvfb; Bedienung per Maus und Tastatur, zusätzlich headless über `XMLLoader` + `Simulation`).

Bitte jeden Punkt einzeln beheben, jeweils einen Regressionstest in `tests/logicsim/RegressionTests.java` ergänzen und `ant test` laufen lassen. Bestehende Dateiformate (.lsc/.lsm) müssen lesbar bleiben.

---

## 0. Bereits erledigt, nur noch prüfen und committen: SR-Latch

`src/gates/SRLatch.java` ist im Arbeitsverzeichnis bereits ersetzt (noch nicht committet).

- **Alter Fehler:** Nach S = 1 und zurück auf 0 fiel Q wieder auf 0 (keine Speicherwirkung); bei S = R = 1 lieferte der Baustein Q = Q̄ = 1 statt 0/0.
- **Neue Umsetzung:** verhaltensbasiert ohne interne NOR-Gatter. S = 1, R = 0 → Q = 1; S = 0, R = 1 → Q = 0; S = R = 0 → halten; S = R = 1 → Q = Q̄ = 0 (NOR-Verhalten). `reset()` setzt Q = 0, Q̄ = 1.
- **Aufgabe:** Regressionstest ergänzen (Folge S/R: 00, 10, 00, 01, 00, 10, 11, 01 → Q/Q̄: 01, 10, 10, 01, 01, 10, 00, 01), committen und Release neu bauen.

---

## 1. Neu angeschlossene Kabel übernehmen den aktuellen Pegel nicht (hoch)

**Reproduktion:** NOT-Gatter und LED einfügen, Ausgang des NOT mit der LED verbinden, Simulieren starten. Die LED bleibt aus, obwohl der NOT-Ausgang HIGH ist. Erst nach Klick auf **Reset** leuchtet sie. Dasselbe gilt für frisch eingefügte Flipflops (Q̄ = 1 erscheint erst nach Reset oder dem ersten Eingangswechsel).

**Ursache:** Ein neues Kabel wird zwar in `Circuit.addWire()` aufgenommen und in `LSPanel` (Kabel-Ende auf Pin, ca. Z. 284–295) mit `connect()` angeschlossen. Der aktuelle Pegel des Quell-Pins wird aber nicht an das Kabel und das Ziel weitergegeben. Nur `Circuit.reset()` (beim Laden bzw. über die Reset-Schaltfläche) berechnet alles neu.

**Vorschlag:**
- Nach dem Fertigstellen eines Kabels (manuell und bei der automatischen Verkabelung) den Pegel des Ausgangs-Pins als Ereignis an das neue Kabel senden.
- Zusätzlich beim Start der Simulation (`LSFrame.actionSimulate`) einmal `lspanel.circuit.reset()` ausführen.

**Akzeptanz:** NOT → LED verbinden und Simulation starten: Die LED leuchtet sofort, ohne Reset.

---

## 2. Textfeld: klickbare Fläche liegt neben dem sichtbaren Text (mittel)

**Reproduktion:** Textfeld einfügen und mit Rechtsklick auf die Mitte des Wortes „Text“ klicken. Es erscheint kein Kontextmenü. Nur ein Klick auf den Wortanfang trifft.

**Ursache:** `gates/TextLabel.java`: `drawRotated()` und `drawActiveFrame()` passen `width`/`height` an die Textbreite an, zeichnen den Text aber weiter zentriert um den alten Mittelpunkt `xc/yc`. `getBoundingBox()` bzw. `insideFrame()` verwenden dagegen `(x, y, width, height)`. Klickfläche und Auswahlrahmen sind deshalb gegenüber dem Text verschoben.

**Vorschlag:** Text an `x/y` ausrichten (z. B. linksbündig ab `x`, oder `xc/yc` nach jeder Größenänderung neu berechnen), sodass Auswahlrahmen, Klickfläche und Text deckungsgleich sind. Danach prüfen, dass vorhandene Dateien mit Textfeldern (z. B. `circuits/*.lsc`) nicht sichtbar verrutschen.

**Akzeptanz:** Rechtsklick auf jede Stelle des sichtbaren Textes öffnet das Kontextmenü; der Auswahlrahmen umschließt den Text.

---

## 3. Automatische Verkabelung erzeugt beim Ziehen ungewollte Kabel (mittel)

**Reproduktion:** SR-Latch einfügen, dann eine LED aus der Liste einfügen und quer über die Arbeitsfläche an ihren Platz ziehen. Fährt dabei der LED-Eingang über einen Ausgang des Latch, entsteht ein schräges Kabel, das bleibt.

**Ursache:** `LSPanel` → `mouseDragged()` (ca. Z. 117–150) prüft bei *jedem* Zwischenschritt des Ziehens, ob sich Pins berühren, und legt sofort ein Kabel an.

**Vorschlag:** Die automatische Verkabelung erst in `mouseReleased()` für die Endposition ausführen. Optional einen deutlichen Hinweis in der Statuszeile anzeigen.

**Akzeptanz:** Ein Bauteil über fremde Pins hinwegziehen erzeugt kein Kabel. Wird es so abgelegt, dass Pins exakt aufeinanderliegen, entsteht weiterhin ein Kabel.

---

## 4. Rückgekoppelte Schaltungen schwingen endlos, auch Module in der Bauteilliste (mittel)

**Reproduktion A:** Zwei NOR-Gatter kreuzweise rückkoppeln (RS-Flipflop), beide Eingänge auf 0, Simulation starten. Die Ausgänge wechseln endlos zwischen 11 und 00 (LEDs flackern), und die CPU-Last liegt bei etwa 100 %.

**Reproduktion B:** Ein Modul mit einer solchen Rückkopplung (z. B. RS-Flipflop aus NOR-Gattern als `.lsm`) in den Ordner `modules` legen. Schon beim Programmstart läuft die Simulation dauerhaft (CPU ca. 100 %), obwohl keine Datei geöffnet ist. `Simulation.awaitIdle()` wird nie erreicht.

**Ursache:**
- A: Bei symmetrischem Start und gleicher Verzögerung beider Gatter ist das im ereignisgesteuerten Modell eine echte Dauerschwingung. In echter Hardware entscheidet hier der Zufall.
- B: `App.loadModules()` erzeugt für die Bauteilliste Modul-Instanzen (`new Module(name)`), deren interne Schaltung an die globale `Simulation` angebunden ist und dort weiterläuft.

**Vorschlag:**
- B: Vorlagen-Instanzen der Bauteilliste nicht simulieren (z. B. keine Ereignisse für Module erzeugen, die nicht in `lspanel.circuit` liegen, oder die Vorlage erst beim Einfügen vollständig aufbauen).
- A: Schwingungen erkennen (z. B. mehr als N Ereignisse pro Bauteil ohne Benutzereingabe) und dann anhalten und in der Statuszeile „Schaltung schwingt (instabiler Zustand)“ melden. Alternativ eine kleine zufällige Verzögerung einbauen, damit sich die Schaltung wie echte Hardware in einen der beiden Zustände einschwingt.

**Akzeptanz:** Mit einem rückgekoppelten Modul im Ordner `modules` ist LogicSim nach dem Start im Leerlauf. Eine NOR-Rückkopplung mit S = R = 0 beim Start erzeugt keine unbegrenzte CPU-Last.

---

## 5. Abzweigungen nur im Modus „Experte“ möglich (niedrig)

**Beobachtung:** Ein Signal an mehrere Eingänge zu führen, gelingt nur, wenn man ein Kabel am Eingang beginnt und auf einem vorhandenen Kabel beendet. `LSPanel` (ca. Z. 286 und 299) erlaubt das nur bei `mode=expert`. Im Modus „Normal“ kann man außerdem keinen Knickpunkt auf ein vorhandenes Kabel setzen. Ein zweites Kabel ab demselben Ausgang muss deshalb zwangsläufig mit einem Klick auf das erste Kabel beginnen und wird abgewiesen.

**Vorschlag:** Auch im Normalmodus das Beenden eines Kabels auf einem vorhandenen Kabel (Abzweig-Punkt) erlauben, oder Abzweigen per Shift + Klick auf ein Kabel (wie in LogicSim 2).

---

## 6. Kleinigkeiten (niedrig)

- `JOptionPane`-Dialoge zeigen auf nicht-deutschen Systemen „Cancel“ statt „Abbrechen“. Abhilfe: Texte über `UIManager` (`OptionPane.cancelButtonText`, `OptionPane.okButtonText`) aus `I18N` setzen.
- Im Release liegen `modules/HA.lsm` und `modules/FA.lsm`. Für den Unterricht kollidieren sie mit den Modulen, die Schülerinnen und Schüler selbst bauen. Vorschlag: Beispielmodule in einen Unterordner `beispiele/` verschieben und `circuits/Adder.lsc` entsprechend anpassen.
- In der Bauteilliste erscheint eine Gruppe „Test“ mit dem Bauteil „Test“ (`gates/TEST.class`). Für Release-Builds ausblenden.


---

## Bearbeitungsstand 29.10.2026

Alle Punkte umgesetzt; nach jedem Punkt wurde `ant test` ausgeführt.

| Punkt | Umsetzung und Regressionstest |
| --- | --- |
| 0 | Vorhandenen SR-Latch geprüft, definierte Startausgänge ergänzt; vollständige S/R-Sequenz und Reset getestet. |
| 1 | Neue manuelle, automatische und kopierte Kabel übernehmen den Quellpegel; Simulationsstart initialisiert die Schaltung. NOT, Flipflop, LOW-Pegel und Abzweig ohne zusätzlichen Zählertakt getestet. |
| 2 | Historischen Textmittelpunkt beibehalten; Klickfläche und Auswahlrahmen folgen den Textmaßen. Gerenderte Textpixel inklusive Überstrich, vier Drehungen und Speichern/Laden getestet. |
| 3 | Automatische Verkabelung erst beim Loslassen, nur bei exakt deckungsgleichen Pins. Überqueren und anschließendes Ablegen getestet. |
| 4 | Modulvorlagen erzeugen keine Simulationsereignisse. Nach mehr als 4096 Ereignissen am selben Bauteil ohne Ruhephase/externen Impuls: Stopp und Statusmeldung „Schaltung schwingt (instabiler Zustand)“. NOR-Rückkopplung, Vorlagen und tatsächlich eingefügtes Rückkopplungsmodul getestet. |
| 5 | Im Normalmodus Kabel an Eingängen beginnen und auf Kabeln/Abzweigpunkten beenden; Shift-Klick beginnt einen Abzweig auf einem Kabel. Pegelübernahme und Dateirundlauf getestet. |
| 6 | Dialogknöpfe aus I18N; HA/FA unter `modules/beispiele/` mit eigenen Modulnamen und aktualisiertem Adder-Beispiel. Alte HA/FA-Referenzen bleiben lesbar, gleichnamige eigene Module haben Vorrang. TEST bleibt für alte Dateien verfügbar, ist aber ausgeblendet. Alle drei Änderungen durch Regressionstests abgedeckt. |

Abschlussprüfung: `ant clean test dist` sowie Regressionstests gegen das entpackte
Release mit dessen JAR, Gatterklassen und tatsächlicher Bauteillisten-Initialisierung.
Die Suite umfasst 52 Tests einschließlich aller mitgelieferten Beispielschaltungen.
Release: `release/LogicSim-3.5-20261029.zip`.

Beim ersten Abschluss war kein Commit möglich: `git add`/`git commit` scheiterten
beim Erstellen von `.git/index.lock` mit `Permission denied`. Die Änderungen
blieben zunächst im Arbeitsverzeichnis liegen.

---

## Nachtrag 29.10.2026 (Test des neuen Releases)

Getestet: Release `LogicSim-3.5-20261029.zip` (Stand nach den Korrekturen) mit allen Fortbildungsdateien und per Maus/Tastatur in der Oberfläche. Punkte 0–6 bestätigt: Pegelübernahme ohne Reset, Klickfläche von Textfeldern, automatische Verkabelung erst beim Ablegen, Abzweigungen im Normalmodus, Module in der Bauteilliste ohne Dauerlast. Halbaddierer und Volladdierer (Modul im Modul) per Oberfläche gebaut und mit allen Kombinationen geprüft, ebenso SR-Latch und die Schaltungen mit Rückkopplung.

### 7. Nach „Schaltung schwingt“ kommt man nicht mehr aus dem Zustand heraus (mittel)

**Reproduktion:** Zwei NOR-Gatter kreuzweise rückkoppeln, Schalter S und R stehen beide auf 0, Simulieren starten. Die Statuszeile meldet „Schaltung schwingt (instabiler Zustand)“, und die Simulation wird angehalten (`Simulation.isRunning() == false`).

- Die Schaltfläche **Simulieren** bleibt dabei optisch gedrückt. Der nächste Klick schaltet sie nur „aus“, erst der übernächste startet wieder.
- Beim Neustart schwingt die Schaltung sofort wieder, weil S = R = 0 unverändert ist. Schalter lassen sich aber nur bei laufender Simulation umlegen. Wer ein RS-Flipflop selbst aus NOR-Gattern baut und mit beiden Schaltern auf 0 startet, kommt so nicht mehr weiter, ohne die Schalter zu löschen oder die Datei zu bearbeiten.

**Vorschlag (eines davon):**
- a) Statt anzuhalten den instabilen Zustand zufällig auflösen, wie in echter Hardware: Beim Erkennen der Schwingung eines der beteiligten Ereignisse verwerfen bzw. verzögern, sodass die Schaltung in einen der beiden stabilen Zustände fällt. Die Statusmeldung kann bleiben („instabiler Zustand – zufällig aufgelöst“).
- b) Simulation angehalten lassen, aber die Schaltfläche Simulieren zurücksetzen und das Umlegen von Schaltern auch im angehaltenen Zustand erlauben (ohne Signalweitergabe bis zum nächsten Start).

**Akzeptanz:** NOR-Rückkopplung mit S = R = 0 starten. Danach lässt sich ohne Bearbeiten der Schaltung ein definierter Zustand herstellen (z. B. R einschalten), und die Schaltfläche Simulieren zeigt den tatsächlichen Zustand.

Für die Fortbildungsmaterialien ist das unkritisch: Die Datei `04_run_flipflop_innenleben.lsc` startet mit R = 1, und in der Oberfläche kann man S und R nur nacheinander umschalten.

#### Erledigt am 29.10.2026

Variante b umgesetzt: Nach dem Schwingungsstopp lassen sich Schalter mit einem
normalen Linksklick umlegen. Dabei ändern sich nur Schalterpegel und gespeicherter
Schalterzustand; die angeschlossene Schaltung erhält den neuen Pegel erst beim
nächsten Start. Taster lassen sich ebenfalls drücken und loslassen, ohne im
angehaltenen Zustand Signale weiterzugeben. Im normalen Bearbeitungsmodus bleibt
das Auswählen und Verschieben von Schaltern erhalten.

Die Instabilitätsmeldung ist direkt an die Simulieren-Schaltfläche gebunden und
setzt deren Auswahl im Swing-Thread zurück. Eine verspätete Meldung aus dem
vorherigen Lauf verändert einen inzwischen neu gestarteten Lauf nicht.

Regressionstests in `tests/logicsim/RegressionTests.java`: NOR-Rückkopplung mit
S = R = 0 bis zum Schwingungsstopp, S bzw. R per Maus umlegen (einschließlich
wiederholten Umschaltens und Tasterbedienung), keine vorzeitige Signalweitergabe,
Neustart mit einem Klick und Halten beider möglichen stabilen Zustände nach dem
Zurückschalten auf S = R = 0. Zusätzlich wird eine verspätete UI-Meldung geprüft.

Prüfung: `ant test` – **54 Tests erfolgreich, 0 fehlgeschlagen**.
Release mit `ant dist` neu gebaut: `release/LogicSim-3.5-20261029.zip`.
Dieselben 54 Tests bestehen auch gegen das entpackte Release mit dessen JAR,
Gatterklassen und tatsächlicher Initialisierung der Bauteilliste.
