# App — aplikacja na smartfon

Aplikacja na smartfon (Android natywny, Kotlin) — czytnik tekstu z lektorem
(TTS) dla osób niewidomych. Czyta tekst z plików **PDF**, **TXT** i **EPUB**
i odtwarza go głosem. Sterowana przyciskami ekranowymi oraz klawiaturą
(w tym BLE z ESP32).

## Funkcje

- **Wybór pliku** — PDF (pdfbox-android), TXT, EPUB (własny parser ZIP+XHTML).
  PDFBox wymaga inicjalizacji `PDFBoxResourceLoader.init(context)` przed
  pierwszym użyciem (ładowanie zasobów, np. glyphlist) — zrobione w
  `DocumentParser.initPdfbox()`.
- **Preprocessing tekstu** — usuwa nagłówki, stopki, numery stron i zbędne
  białe znaki.
- **Lektor (TTS)** — odtwarzanie, pauza, start od początku, poprzednie /
  następne zdanie, następna strona, prędkość, głośność.
- **Wybór polskiego lektora** — przełącza tylko między głosami polskimi
  (jeśli dostępne).
- **Zapamiętywanie pozycji** — dla każdego pliku zapamiętywana jest pozycja
  w tekście; wznowienie następuje od początku zdania, w którym przerwano.
  "Start od początku" resetuje punkt wznowienia.
- **Sterowanie bez wzroku** — każde naciśnięcie przycisku wstrzymuje czytanie,
  odtwarza słowny opis funkcji; ponowne naciśnięcie tego samego przycisku
  w ciągu 5 s wykonuje funkcję. Po 5 s bezczynności wraca do czytania.
  Wykonanie funkcji i upływ czasu sygnalizowane są różnymi beep.
- **Powiadomienia o baterii** — komunikat głosowy i powiadomienie przy
  niskim poziomie baterii.

## Budowa (Android Studio)

1. Otwórz katalog `app/` jako projekt.
2. Zbuduj i uruchom na urządzeniu (Run ▶).

### Z linii poleceń

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Struktura

```
app/
├── app/src/main/java/com/blindreader/
│   ├── MainActivity.kt      # UI, przyciski, obsługa klawiatury
│   ├── ReaderService.kt     # TTS, odtwarzanie, komendy, pozycje, bateria
│   ├── DocumentParser.kt    # PDF / TXT / EPUB
│   └── TextPreprocessor.kt  # usuwanie nagłówków, stron itp.
└── app/src/main/res/        # layout, zasoby, ikona
```

## Sterowanie klawiaturą

| Klawisz | Funkcja |
|---|---|
| Enter / D-pad center | Odtwarzaj / wznów |
| Spacja | Pauza |
| Strzałka w górę / dół | Poprzednie / następne zdanie |
| PageDown | Następna strona |
| Strzałka w lewo / prawo | Zmniejsz / zwiększ prędkość |
| Głośność − / + | Zmniejsz / zwiększ głośność |
| V | Zmień lektora |
