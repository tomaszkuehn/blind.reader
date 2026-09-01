/*
 * BLE HID phone controller for ESP32 (ESP32-WROOM / NodeMCU ESP32S).
 *
 * The ESP32 advertises as a composite BLE HID device (Keyboard + Mouse +
 * Consumer Control). A host (phone) pairs with it and receives key presses,
 * pointer moves/clicks and media keys.
 *
 * Commands are received over UART0 (the USB-serial bridge on the devkit).
 * Each command is a single line terminated by '\n'.
 *
 * Protocol (ASCII, one command per line):
 *   key <hidcode> [modifiers]      press+release a key (modifiers: ctrl,shift,alt,gui)
 *   text <string>                  type a string (ASCII)
 *   move <dx> <dy>                 relative pointer move
 *   click [left|right|middle]      click a mouse button
 *   scroll <delta>                 vertical wheel
 *   media <play|pause|volup|voldown|mute|next|prev|stop>
 *   help                           print this help
 *   status                         print connection status
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <inttypes.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_system.h"
#include "esp_log.h"
#include "driver/uart.h"
#include "nvs_flash.h"
#include "esp_bt.h"

#include "esp_hidd.h"
#include "esp_hid_gap.h"
#include "hid_report_maps.h"

#if CONFIG_BT_BLE_ENABLED
#include "esp_gap_ble_api.h"
#include "esp_gatts_api.h"
#include "esp_gatt_defs.h"
#endif
#include "esp_bt_main.h"
#include "esp_bt_device.h"

static const char *TAG = "BLE_HID_CTRL";

static esp_hidd_dev_t *s_hid_dev = NULL;
static bool s_connected = false;

/* ------------------------------------------------------------------ */
/* HID report senders                                                  */
/* ------------------------------------------------------------------ */

static void send_keyboard_report(const uint8_t *report, size_t len)
{
    if (s_hid_dev) {
        esp_err_t err = esp_hidd_dev_input_set(s_hid_dev, 0, RPT_ID_KEYBOARD, (uint8_t *)report, len);
        ESP_LOGI(TAG, "input_set err=%d len=%d", err, (int)len);
    }
}

/* ------------------------------------------------------------------ */
/* Keyboard helpers                                                    */
/* ------------------------------------------------------------------ */

#define MOD_LEFT_CTRL   0x01
#define MOD_LEFT_SHIFT  0x02
#define MOD_LEFT_ALT    0x04
#define MOD_LEFT_GUI    0x08

static void key_press_release(uint8_t keycode, uint8_t modifiers)
{
    uint8_t report[RPT_KEYBOARD_LEN] = {0};
    report[0] = modifiers;
    report[2] = keycode;
    send_keyboard_report(report, sizeof(report));
    vTaskDelay(pdMS_TO_TICKS(30));
    memset(report, 0, sizeof(report));
    send_keyboard_report(report, sizeof(report));
    vTaskDelay(pdMS_TO_TICKS(10));
}

/* Map an ASCII char to a USB HID keycode + modifier. Returns 0 if unmapped. */
static uint8_t char_to_hid(char ch, uint8_t *modifiers)
{
    *modifiers = 0;
    if (ch >= 'a' && ch <= 'z') {
        return (uint8_t)(4 + (ch - 'a'));
    }
    if (ch >= 'A' && ch <= 'Z') {
        *modifiers = MOD_LEFT_SHIFT;
        return (uint8_t)(4 + (ch - 'A'));
    }
    if (ch >= '1' && ch <= '9') {
        return (uint8_t)(30 + (ch - '1'));
    }
    if (ch == '0') {
        return 39;
    }
    switch (ch) {
    case ' ':  return 0x2C;
    case '\n': return 0x28;
    case '\t': return 0x2B;
    case '.':  return 0x37;
    case ',':  return 0x36;
    case '/':  return 0x38;
    case '\\': return 0x31;
    case ';':  return 0x33;
    case '\'': return 0x34;
    case '[':  return 0x2F;
    case ']':  return 0x30;
    case '-':  return 0x2D;
    case '=':  return 0x2E;
    case '`':  return 0x35;
    case '?':  *modifiers = MOD_LEFT_SHIFT; return 0x38;
    case '!':  *modifiers = MOD_LEFT_SHIFT; return 30;
    case '@':  *modifiers = MOD_LEFT_SHIFT; return 31;
    case '#':  *modifiers = MOD_LEFT_SHIFT; return 32;
    case '$':  *modifiers = MOD_LEFT_SHIFT; return 33;
    case '%':  *modifiers = MOD_LEFT_SHIFT; return 34;
    case '^':  *modifiers = MOD_LEFT_SHIFT; return 35;
    case '&':  *modifiers = MOD_LEFT_SHIFT; return 36;
    case '*':  *modifiers = MOD_LEFT_SHIFT; return 37;
    case '(':  *modifiers = MOD_LEFT_SHIFT; return 38;
    case ')':  *modifiers = MOD_LEFT_SHIFT; return 39;
    case '_':  *modifiers = MOD_LEFT_SHIFT; return 0x2D;
    case '+':  *modifiers = MOD_LEFT_SHIFT; return 0x2E;
    case '{':  *modifiers = MOD_LEFT_SHIFT; return 0x2F;
    case '}':  *modifiers = MOD_LEFT_SHIFT; return 0x30;
    case ':':  *modifiers = MOD_LEFT_SHIFT; return 0x33;
    case '"':  *modifiers = MOD_LEFT_SHIFT; return 0x34;
    case '|':  *modifiers = MOD_LEFT_SHIFT; return 0x31;
    case '~':  *modifiers = MOD_LEFT_SHIFT; return 0x35;
    case '<':  *modifiers = MOD_LEFT_SHIFT; return 0x36;
    case '>':  *modifiers = MOD_LEFT_SHIFT; return 0x37;
    default:   return 0;
    }
}

static void type_text(const char *text)
{
    for (const char *p = text; *p; p++) {
        uint8_t mods;
        uint8_t code = char_to_hid(*p, &mods);
        if (code) {
            key_press_release(code, mods);
        }
    }
}

/* ------------------------------------------------------------------ */
/* Command parser                                                      */
/* ------------------------------------------------------------------ */

static void print_help(void)
{
    printf(
        "BLE HID controller commands:\n"
        "  key <hidcode> [modifiers]   press+release key (mods: ctrl,shift,alt,gui)\n"
        "  text <string>              type ASCII string\n"
        "  status                      print connection status\n"
        "  help                        this help\n");
}

static void cmd_status(void)
{
    printf("connected: %s\n", s_connected ? "yes" : "no");
}

static void cmd_key(char *args)
{
    char *code_str = strtok(args, " ");
    char *mods_str = strtok(NULL, " ");
    if (!code_str) {
        printf("usage: key <hidcode> [modifiers]\n");
        return;
    }
    uint8_t code = (uint8_t)strtoul(code_str, NULL, 0);
    uint8_t mods = 0;
    if (mods_str) {
        char *m = strtok(mods_str, ",");
        while (m) {
            if      (strcmp(m, "ctrl") == 0)  mods |= MOD_LEFT_CTRL;
            else if (strcmp(m, "shift") == 0) mods |= MOD_LEFT_SHIFT;
            else if (strcmp(m, "alt") == 0)   mods |= MOD_LEFT_ALT;
            else if (strcmp(m, "gui") == 0)   mods |= MOD_LEFT_GUI;
            m = strtok(NULL, ",");
        }
    }
    key_press_release(code, mods);
}

static void cmd_text(char *args)
{
    if (!args) {
        printf("usage: text <string>\n");
        return;
    }
    type_text(args);
}

static void handle_line(char *line)
{
    /* strip trailing newline / CR */
    char *nl = strchr(line, '\n');
    if (nl) *nl = 0;
    char *cr = strchr(line, '\r');
    if (cr) *cr = 0;

    char *cmd = strtok(line, " ");
    if (!cmd) return;
    char *args = strtok(NULL, "");

    if      (strcmp(cmd, "help") == 0)   print_help();
    else if (strcmp(cmd, "status") == 0) cmd_status();
    else if (strcmp(cmd, "key") == 0)    cmd_key(args);
    else if (strcmp(cmd, "text") == 0)   cmd_text(args);
    else printf("unknown command: %s (type 'help')\n", cmd);
}

/* ------------------------------------------------------------------ */
/* UART console task                                                   */
/* ------------------------------------------------------------------ */

#define UART_PORT 0
#define UART_BAUD 115200

static void uart_console_task(void *arg)
{
    char line[256];
    size_t pos = 0;

    while (1) {
        uint8_t c;
        int n = uart_read_bytes(UART_PORT, &c, 1, pdMS_TO_TICKS(20));
        if (n <= 0) {
            continue;
        }
        if (c == '\n' || c == '\r') {
            if (pos > 0) {
                line[pos] = 0;
                handle_line(line);
                pos = 0;
            }
        } else if (pos < sizeof(line) - 1) {
            line[pos++] = (char)c;
        }
    }
}

/* ------------------------------------------------------------------ */
/* HID event callback                                                  */
/* ------------------------------------------------------------------ */

/* Referenced by esp_hid_gap.c on connection. No-op: our UART task is
 * always running, so there is nothing to start/stop per connection. */
void ble_hid_task_start_up(void)
{
}

static void hid_event_callback(void *handler_args, esp_event_base_t base,
                               int32_t id, void *event_data)
{
    esp_hidd_event_t event = (esp_hidd_event_t)id;
    (void)event_data;

    switch (event) {
    case ESP_HIDD_START_EVENT:
        ESP_LOGI(TAG, "HID start, advertising");
        esp_hid_ble_gap_adv_start();
        break;
    case ESP_HIDD_CONNECT_EVENT:
        ESP_LOGI(TAG, "HID connected");
        s_connected = true;
        break;
    case ESP_HIDD_DISCONNECT_EVENT:
        ESP_LOGI(TAG, "HID disconnected, re-advertising");
        s_connected = false;
        esp_hid_ble_gap_adv_start();
        break;
    default:
        break;
    }
}

/* ------------------------------------------------------------------ */
/* app_main                                                            */
/* ------------------------------------------------------------------ */

void app_main(void)
{
    esp_err_t ret;

    ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    /* UART console on UART0 (USB-serial bridge) */
    uart_config_t uart_cfg = {
        .baud_rate = UART_BAUD,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };
    uart_driver_install(UART_PORT, 1024, 0, 0, NULL, 0);
    uart_param_config(UART_PORT, &uart_cfg);
    uart_set_pin(UART_PORT, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE);

    ESP_LOGI(TAG, "initializing HID GAP");
    ret = esp_hid_gap_init(HID_DEV_MODE);
    ESP_ERROR_CHECK(ret);

    static esp_hid_raw_report_map_t report_maps[] = {
        { .data = keyboardReportMap, .len = sizeof(keyboardReportMap) },
    };

    static esp_hid_device_config_t hid_config = {
        .vendor_id         = 0x16C0,
        .product_id        = 0x05DF,
        .version           = 0x0100,
        .device_name       = "ESP Phone Controller",
        .manufacturer_name = "Espressif",
        .serial_number     = "1234567890",
        .report_maps       = report_maps,
        .report_maps_len   = 1,
    };

    ret = esp_hid_ble_gap_adv_init(ESP_HID_APPEARANCE_KEYBOARD, hid_config.device_name);
    ESP_ERROR_CHECK(ret);

#if CONFIG_BT_BLE_ENABLED
    if ((ret = esp_ble_gatts_register_callback(esp_hidd_gatts_event_handler)) != ESP_OK) {
        ESP_LOGE(TAG, "GATTS register callback failed: %d", ret);
        return;
    }
#endif

    ESP_LOGI(TAG, "initializing HID device");
    ret = esp_hidd_dev_init(&hid_config, ESP_HID_TRANSPORT_BLE, hid_event_callback, &s_hid_dev);
    ESP_ERROR_CHECK(ret);

    xTaskCreate(uart_console_task, "uart_console", 4096, NULL, 5, NULL);
    ESP_LOGI(TAG, "ready. Pair with 'ESP Phone Controller' and send commands over UART.");
}
