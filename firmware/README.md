# BLE HID Phone Controller (ESP32)

Automatyczna kontrola telefonu przez Bluetooth. ESP32 emuluje **bezprzewodową
klawiaturę** (BLE HID). Telefon paruje się z ESP32 jak z normalną klawiaturą
Bluetooth i odbiera naciśnięcia klawiszy oraz wpisywany tekst.

Działa na płytach z klasycznym ESP32 (ESP32-WROOM devkit v1/v4, NodeMCU ESP32S),
bo używa **BLE**, a nie USB HID (klasyczny ESP32 nie ma natywnego USB OTG).

## Sprzęt

- ESP32 (dowolna płyta z BLE)
- Kabel USB (do zasilania + kanału sterowania przez mostek USB-UART)

## Budowa (WSL)

```bash
cd /mnt/d/kody/blind.reader
export IDF_PATH=~/esp/esp-idf
source $IDF_PATH/export.sh
idf.py set-target esp32
idf.py build
```

## Wgranie

WSL2 nie przekazuje portów COM Windows, więc wgrywamy z Windows (esptool):

```powershell
esptool.exe --chip esp32 -p COM5 -b 460800 --before default-reset --after hard-reset `
  write-flash --flash-mode dio --flash-size 2MB --flash-freq 40m `
  0x1000 build\bootloader\bootloader.bin `
  0x8000 build\partition_table\partition-table.bin `
  0x10000 build\ble_hid_phone_controller.bin
```

## Użycie

1. Wgraj firmware i podłącz ESP32 przez USB.
2. Na telefonie włącz Bluetooth i sparuj z urządzeniem **"ESP Phone Controller"**.
3. Otwórz pole tekstowe (notatnik, SMS, wyszukiwarka) i **dotknij je**, żeby
   kursor był aktywny.
4. Wysyłaj komendy przez port szeregowy (np. PuTTY / Arduino Serial Monitor,
   **115200 baud**).

## Protokół (jedna komenda na linię, zakończona Enter)

| Komenda | Opis |
|---|---|
| `text <string>` | Wpisz tekst (ASCII) |
| `key <hidcode> [mods]` | Naciśnij klawisz (kod HID). `mods`: `ctrl,shift,alt,gui` |
| `status` | Czy telefon jest połączony |
| `help` | Pomoc |

### Przykłady

```
text hello world
key 0x28          # Enter
key 0x2A ctrl     # Ctrl+A
status
```

## Kody klawiszy HID (przykłady)

| Klawisz | Kod | Klawisz | Kod |
|---|---|---|---|
| Enter | 0x28 | Spacja | 0x2C |
| Backspace | 0x2A | Tab | 0x2B |
| Esc | 0x29 | Strzałka w górę | 0x52 |
| Strzałka w dół | 0x51 | Strzałka w lewo | 0x50 |
| Strzałka w prawo | 0x4F | Home | 0x4A |
| End | 0x4D | PageUp | 0x4B |
| PageDown | 0x4E | Delete | 0x4C |
| F1..F12 | 0x3A..0x45 | | |

## Uwagi

- Telefon musi obsługiwać BLE HID (Android/iOS tak).
- Po rozłączeniu ESP32 automatycznie wznawia reklamowanie.
- Kanał sterowania to UART0 (mostek USB-UART na płycie). Można też wysyłać
  komendy z komputera przez dowolny terminal szeregowy.
- **Ważne:** pole tekstowe na telefonie musi być aktywne (kursor w polu), żeby
  tekst został wpisany.
