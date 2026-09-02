# App — aplikacja na smartfon

Aplikacja na smartfon (Android natywny, Kotlin) — czytnik tekstu z lektorem
(TTS) dla osób niewidomych. Czyta tekst z plików **PDF**, **TXT** i **EPUB**
i odtwarza go głosem. Sterowana przyciskami ekranowymi oraz klawiaturą
(w tym BLE z ESP32).

## Funkcje

- **Wybór pliku** — aplikacja czyta z folderu `Documents/Reader` na
  urządzeniu. Po wybraniu "Otwórz plik" główny ekran przechodzi w tryb wyboru:
  klawiszami strzałek przechodzi się między dostępnymi tytułami (PDF, TXT,
  EPUB), lektor odczytuje nazwę pliku, a Enter/Odtwarzaj wczytuje i odtwarza
  wybrany plik. W trybie wyboru aktywne są tylko: poprzednie zdanie, następne
  zdanie i odtwarzaj. Wymaga uprawnienia "All files access"
  (MANAGE_EXTERNAL_STORAGE). PDFBox wymaga inicjalizacji
  `PDFBoxResourceLoader.init(context)` przed pierwszym użyciem (ładowanie
  zasobów, np. glyphlist) — zrobione w `DocumentParser.initPdfbox()`.
- **Preprocessing tekstu** — usuwa nagłówki, stopki, numery stron i zbędne
  białe znaki. Dla PDF headery/footery wykrywane są per strona: linia
  powtarzająca się na górze lub dole ≥60% stron jest uznawana za nagłówek/
  stopkę i pomijana. Usuwane są też odnośniki do przypisów (superscript)
  zaraz po słowie, np. `słowo1`, `słowo[2]`, `słowo*` — to nie część słowa,
  tylko odnośnik.
- **Lektor (TTS)** — odtwarzanie, pauza, start od początku, poprzednie /
  następne zdanie, następna strona, prędkość, głośność.
- **Naturalne czytanie** — po dwukropku lektor robi krótką pauzę; tekst
  w nawiasach czyta szybciej, z pauzami przed i po nawiasie. Pauzy to
  prawdziwe pauzy czasowe (bez wymawiania znaków), a szybsze czytanie
  realizowane jest przez podział zdania na segmenty z różną prędkością —
  bez tagów SSML, które niektóre silniki TTS czytałyby dosłownie.
- **Numery stron zgodne z PDF** — PDF parsowany jest strona po stronie, więc
  po przeskoczeniu do następnej strony lektor odtwarza prawdziwy numer strony
  z dokumentu, a czytanie zaczyna się od początku kolejnej strony.
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

## Mechanizm potwierdzania komend (wszystkie przyciski)

Każdy przycisk działa w ten sam sposób, dwustopniowo:

1. **Pierwsze naciśnięcie** — czytanie zostaje wstrzymane (jeśli trwa),
   a lektor odtwarza **słowny opis funkcji** przycisku.
2. **Ponowne naciśnięcie tego samego przycisku w ciągu 5 sekund** — funkcja
   zostaje wykonana, a aplikacja odtwarza **dźwięk potwierdzenia** (ACK).
3. **Naciśnięcie innego przycisku** — zaczyna od nowa: wstrzymuje czytanie
   i odtwarza opis nowego przycisku.
4. **Brak naciśnięcia przez 5 sekund** — aplikacja odtwarza **inny beep**
   (upływ czasu), po czym wraca do czytania (jeśli było przerwane) lub czeka
   na następną akcję.

Dzięki temu użytkownik zawsze wie, co zrobi przycisk, zanim go wykona, i może
obsługiwać aplikację w pełni bez użycia wzroku.

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

### Wybór pliku (tryb na głównym ekranie)

Po wybraniu "Otwórz plik" główny ekran przechodzi w tryb wyboru pliku.
Aktywne są tylko: poprzedni plik, następny plik i odtwarzaj. Działają one
zgodnie z opisanym wyżej mechanizmem potwierdzania:

- **Następny plik** — pierwsze naciśnięcie odtwarza "Następny plik",
  ponowne w ciągu 5 s przechodzi do następnego pliku i odtwarza jego nazwę.
- **Poprzedni plik** — analogicznie, przechodzi do poprzedniego pliku.
- **Odtwarzaj** — pierwsze naciśnięcie odtwarza "Odtwarzaj", ponowne w ciągu
  5 s wczytuje wybrany plik, odtwarza "Plik wczytany" i wraca do głównego menu
  (czytanie zaczyna się od zapamiętanej pozycji).

| Klawisz | Funkcja |
|---|---|
| Strzałka w dół / PageDown | Następny plik |
| Strzałka w górę / PageUp | Poprzedni plik |
| Enter / D-pad center | Wczytaj wybrany plik i wróć do menu |
| Wstecz | Wyjście z trybu wyboru |

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
│   ├── MainActivity.kt      # UI, przyciski, obsługa klawiatury, wybór pliku
│   ├── ReaderService.kt     # TTS, odtwarzanie, komendy, pozycje, bateria
│   ├── DocumentParser.kt    # PDF / TXT / EPUB
│   └── TextPreprocessor.kt  # usuwanie nagłówków, stron itp.
└── app/src/main/res/        # layout, zasoby, ikona
```
