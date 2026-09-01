# Blind Reader

Czytnik dla osób niewidomych — urządzenie, które automatycznie kontroluje
smartfon (wpisuje tekst, wysyła gesty i akcje dotykowe), aby ułatwić korzystanie
z aplikacji.

## Struktura projektu

```
blind.reader/
├── firmware/     # Firmware ESP32 (BLE HID — emulacja klawiatury)
├── app/          # Aplikacja na smartfon (Android, Kotlin)
├── hardware/     # Elektronika — schemat i PCB
├── enclosure/    # Obudowa do druku 3D
├── docs/         # Dokumentacja projektu
└── README.md     # Ten plik
```

## Podprojekty

| Katalog | Opis | Status |
|---|---|---|
| `firmware/` | ESP32 emuluje bezprzewodową klawiaturę (BLE HID). Sterowanie przez UART. | Działa |
| `app/` | Aplikacja na smartfon (Android natywny, Kotlin). | Planowane |
| `hardware/` | Schemat i PCB urządzenia. | Planowane |
| `enclosure/` | Obudowa do druku 3D. | Planowane |
| `docs/` | Dokumentacja projektu. | Planowane |

## Szybki start

Zobacz [firmware/README.md](firmware/README.md) — budowa, wgranie i protokół
sterowania ESP32.
