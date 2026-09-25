// One-button Bluetooth LE remote for the Allenamento app.
//
// Board: Seeed Studio XIAO nRF52840 (core "Seeed nRF52 Boards", not the mbed one).
// Wiring: push button between D1 and GND; LiPo on the BAT+/BAT- pads underneath.
//
// Protocol (must match RemoteButton.kt in the app):
//   service 7e1a0001-5c3b-4f7e-9a2d-6b1f0c2e8a40
//   button  7e1a0002-...  notify, 1 byte: 1 = pressed, 0 = released
//   standard Battery Service (0x180F) with the charge in percent
// The firmware only reports presses; the app decides what a click or long press does.
//
// Power: advertises until the app connects; after SLEEP_AFTER_MS without a connection it
// goes to System OFF (a few uA) and a press on the button wakes it up again.

#include <bluefruit.h>

const uint8_t BUTTON_PIN = D1;
const uint32_t DEBOUNCE_MS = 15;
const uint32_t SLEEP_AFTER_MS = 10UL * 60 * 1000;
const uint32_t BATTERY_EVERY_MS = 5UL * 60 * 1000;

// 128-bit UUIDs, least significant byte first.
const uint8_t SERVICE_UUID[16] = {0x40, 0x8a, 0x2e, 0x0c, 0x1f, 0x6b, 0x2d, 0x9a,
                                  0x7e, 0x4f, 0x3b, 0x5c, 0x01, 0x00, 0x1a, 0x7e};
const uint8_t BUTTON_UUID[16] = {0x40, 0x8a, 0x2e, 0x0c, 0x1f, 0x6b, 0x2d, 0x9a,
                                 0x7e, 0x4f, 0x3b, 0x5c, 0x02, 0x00, 0x1a, 0x7e};

BLEService remoteService(SERVICE_UUID);
BLECharacteristic buttonChar(BUTTON_UUID);
BLEBas battery;

SemaphoreHandle_t buttonEdge;
bool buttonDown = false;
uint32_t lastActivity = 0;
uint32_t lastBattery = 0;

// Runs in a task (ISR_DEFERRED), so plain FreeRTOS calls are fine.
void onButtonEdge() { xSemaphoreGive(buttonEdge); }

void onConnect(uint16_t) { lastActivity = millis(); }
void onDisconnect(uint16_t, uint8_t) { lastActivity = millis(); }

uint8_t readBattery() {
  // 1M/510k divider on P0.31, enabled by VBAT_ENABLE low (kept low: see setup).
  analogReference(AR_INTERNAL_2_4);
  analogReadResolution(12);
  uint32_t raw = 0;
  for (int i = 0; i < 8; i++) raw += analogRead(PIN_VBAT);
  float mv = raw / 8.0f * 2400.0f / 4096.0f * 1510.0f / 510.0f;
  // Rough LiPo curve: 3.3 V empty, 4.15 V full.
  int pct = (int)((mv - 3300.0f) * 100.0f / (4150.0f - 3300.0f));
  return (uint8_t)constrain(pct, 0, 100);
}

void updateBattery() {
  uint8_t pct = readBattery();
  if (Bluefruit.connected()) battery.notify(pct); else battery.write(pct);
  lastBattery = millis();
}

void setLed(bool on) {
  // While pressed: blue if connected to the app, red if not. LEDs are active low.
  digitalWrite(LED_BLUE, on && Bluefruit.connected() ? LOW : HIGH);
  digitalWrite(LED_RED, on && !Bluefruit.connected() ? LOW : HIGH);
}

void goToSleep() {
  setLed(false);
  Bluefruit.Advertising.stop();
  // Wake up (with a reset) when the button pulls the pin low.
  nrf_gpio_cfg_sense_input(g_ADigitalPinMap[BUTTON_PIN], NRF_GPIO_PIN_PULLUP, NRF_GPIO_PIN_SENSE_LOW);
  delay(50);
  sd_power_system_off();
}

void setup() {
  // Never drive VBAT_ENABLE high: P0.31 would see the full battery voltage.
  pinMode(VBAT_ENABLE, OUTPUT);
  digitalWrite(VBAT_ENABLE, LOW);
  pinMode(LED_RED, OUTPUT);
  pinMode(LED_GREEN, OUTPUT);
  pinMode(LED_BLUE, OUTPUT);
  digitalWrite(LED_RED, HIGH);
  digitalWrite(LED_GREEN, HIGH);
  digitalWrite(LED_BLUE, HIGH);

  pinMode(BUTTON_PIN, INPUT_PULLUP);
  buttonEdge = xSemaphoreCreateBinary();
  attachInterrupt(digitalPinToInterrupt(BUTTON_PIN), onButtonEdge, CHANGE | ISR_DEFERRED);

  Bluefruit.begin();
  Bluefruit.autoConnLed(false);  // the blinking LED would eat the battery
  Bluefruit.setTxPower(0);
  Bluefruit.setName("Telecomando");
  Bluefruit.Periph.setConnectCallback(onConnect);
  Bluefruit.Periph.setDisconnectCallback(onDisconnect);
  Bluefruit.Periph.setConnInterval(24, 80);  // 30-100 ms: presses arrive quickly

  remoteService.begin();
  buttonChar.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  buttonChar.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  buttonChar.setFixedLen(1);
  buttonChar.begin();
  buttonChar.write8(0);

  battery.begin();
  updateBattery();

  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addService(remoteService);
  Bluefruit.ScanResponse.addName();
  Bluefruit.Advertising.restartOnDisconnect(true);
  Bluefruit.Advertising.setInterval(32, 1600);  // 20 ms for the first 30 s, then 1 s
  Bluefruit.Advertising.setFastTimeout(30);
  Bluefruit.Advertising.start(0);

  lastActivity = millis();
}

void loop() {
  if (xSemaphoreTake(buttonEdge, pdMS_TO_TICKS(1000)) == pdTRUE) {
    delay(DEBOUNCE_MS);
    bool down = digitalRead(BUTTON_PIN) == LOW;
    if (down != buttonDown) {
      buttonDown = down;
      setLed(down);
      if (Bluefruit.connected()) buttonChar.notify8(down ? 1 : 0);
    }
    lastActivity = millis();
  }
  if (Bluefruit.connected() && millis() - lastBattery > BATTERY_EVERY_MS) updateBattery();
  if (!Bluefruit.connected() && !buttonDown && millis() - lastActivity > SLEEP_AFTER_MS) goToSleep();
}
