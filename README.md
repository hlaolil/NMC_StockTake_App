# NMC Pharmacy Stock Take (Android)

A phone companion to the NMC pharmacy web app. You count shelf by shelf on the phone,
read batch and expiry off the box with the camera, and upload the result to the web app.

## The workflow

**Linked to the web app (recommended).** Once: on the web app, Stock Take > *link the phone app* >
Show Link Code, and in the app tap **Scan the Link Code**. Then:

1. **Web app:** open a stock take. Untick groups to count only some of them (cycle counting):
   the start page says when each group was last counted.
2. **App:** the start screen shows the open stock take; tap a group to get its sheet.
3. **Count.** Items come in shelf order. See *Counting* below.
4. **Send to Web App.** The counts wait on the web Stock Take page, highlighted.
5. **Web app:** check them and press **Post Uploaded Counts**.

**Without the link:** download the CSV from the Stock Take page, **Open Count Sheet** in the app,
and at the end **Save File** or **Share**, then upload it there with Read Spreadsheet.

## Counting

- **Matches (N) & Next**: the shelf agrees with the system, batch and expiry too. One tap, no photo.
- Type a count when it differs. Sums work: `3x10+4`. With a pack size on the sheet, `p` counts packs:
  `12p+5` with packs of 28 shows `= 341`. Blank means "not counted"; `0` means none there.
- **Read Label** (or **Matches + Read Label**): only when the batch or expiry on the box differs.
  It fills the boxes for you to check; it never saves by itself.
- **Scan Barcode**: jumps to the item. A new barcode is put on an item once, and is saved to the
  web app with the counts, so after that the box is found by scanning.
- **Shelf**: type the location (e.g. `B3`) where it is missing or wrong; the web app saves it and
  the next sheet comes in that order.
- An expiry within 3 months shows **Short-dated**, a past one **EXPIRED**, while the pack is in your hand.
- **Next not counted / Find an item** (by name, code or shelf) to jump around.
- Everything is kept on the phone as you go, so closing the app loses nothing.

## Several phones at once

Link each phone (each gets its own code). Phones can count the same stock take, or check the same
delivery, at the same time. An item another phone has already done shows *Already counted on ...*,
and Save & Next passes over it, so the phones share the work out by themselves. Counting it again on
purpose is allowed: if the figures differ, the web app flags it in red with both figures and does not
post it until it is counted again.

## Receiving

**Linked:** open the delivery on the web app's Receive page and upload its invoice as usual. On the
phone, the start screen lists open deliveries; tap one and its invoice lines come to the phone. For
each box: **Scan Barcode** to jump to its line, enter the units received (`2p` for two packs), and
**Read Label** for batch and expiry. A count that differs from the invoice shows *Short by ...* at
once. **Send to Web App** fills the delivery's lines; differences are in red there with the note,
and nothing is received until **Add These Lines** is pressed on the delivery page.

It need not be done in one go. Send as often as you like; each send fills in only the lines checked.
On the delivery page, lines not yet checked on the phone start unticked, and Add These Lines leaves
them waiting, so part of an invoice can be received now and the rest later. Getting the lines again
(on another phone, or after clearing) brings back what was already checked.

**Without the link:** open a CSV with Medication and Quantity columns, check each line, export,
and upload it on the delivery (the export gives single units).

## Building

Open this folder in Android Studio (Ladybug 2024.2 or newer), let Gradle sync, then Run on a phone
(Android 8.0 or newer). Build > Build APK(s) gives an APK you can install on other phones.

- Kotlin 2.0.21, Android Gradle Plugin 8.7.3, Gradle 8.11.1 (wrapper included)
- Jetpack Compose (BOM 2024.12.01), CameraX 1.4.1, ML Kit text recognition 16.0.1 and barcode scanning 17.3.0
  (both on the phone; the internet is used only to reach the web app once linked)

## Layout

- `core/` — plain Java, no Android: count sums (`CountExpression`), CSV (`Csv`), finding columns
  (`SheetColumns`), reading batch/expiry from label text (`LabelReader`), short-dated warnings (`Expiry`), the sheet itself (`StockSheet`).
  Checked by `tools/CoreCheck.java`:
  `javac -d build/corecheck app/src/main/java/com/nmc/stocktake/core/*.java tools/CoreCheck.java && java -cp build/corecheck CoreCheck`
- `data/SessionStore.kt` — saves progress to the phone after every change.
- `data/WebLink.kt` — the link to the web app: get sheets, send counts.
- `ocr/OcrProcessor.kt` — ML Kit text recognition, then `LabelReader`.
- `ui/` — screens (`StockTakeApp`), label camera (`CameraScreen`), barcode/QR scanner (`ScanScreen`), state (`StockTakeViewModel`).

The app keeps no medication list of its own; the list always comes from the web app's count sheet,
so names and product codes always match.
