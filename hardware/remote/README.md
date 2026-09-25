# Bluetooth remote

A button worn on the wrist (or around the neck) that tells the app "set done" during a workout,
without touching the media keys or the volume.

- **Click**: start the exercise / set done / skip the rest or the get-ready countdown.
  Ignored during timed sets, so an accidental tap doesn't interrupt them.
- **Long press** (0.7 s): pause / resume.

In the app: Impostazioni → **Telecomando Bluetooth**. During a workout, a Bluetooth icon with the
battery level appears next to the buttons at the top while it's connected.

## Parts

| Part | Notes |
|---|---|
| Seeed Studio XIAO nRF52840 | the plain one, not the Sense |
| LiPo 3.7 V 402030 200 mAh | with protection circuit |
| 12×12×7.3 mm tactile button + round cap | |
| 20 mm velcro strap | or a cord through the slots |

## Wiring

```
Button:   one pin → D1          the other pin → GND
Battery:  red → BAT+            black → BAT-     (pads under the XIAO)
```

The button has 4 pins connected in pairs: use two pins **diagonally opposite** and you're sure to get
both sides of the contact. No resistor needed: the internal pull-up is used.

**Mind the battery polarity**: reversing it burns the charger. The XIAO charges it over USB-C
(at 50 mA, about 4 hours from empty); the charge LED next to the USB port stays on while charging.

## Firmware

1. Arduino IDE → Preferences → Additional boards manager URLs:
   `https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json`
2. Boards manager → install **Seeed nRF52 Boards** (not the "mbed-enabled" one).
3. `adafruit-nrfutil` must be on the PATH: `pip install --user adafruit-nrfutil`.
4. Open `firmware/remote/remote.ino`, board **Seeed XIAO nRF52840**, upload.

Or from a terminal with `arduino-cli`:

```sh
arduino-cli compile --upload -p /dev/ttyACM0 --fqbn Seeeduino:nrf52:xiaonRF52840 firmware/remote
```

If the board no longer shows up over USB: double-click the tiny reset button quickly
to enter the bootloader, then upload again.

### Behaviour

- Right after power-on it advertises (every 20 ms for 30 s, then every second) until the app connects.
- LED while you hold the button: **blue** if connected to the app, **red** if not.
- After 10 minutes with no app connected it goes into deep sleep (a few µA): **press the button to
  wake it up**. That press only wakes it and doesn't reach the app; it reconnects in 1-2 s.
- With 200 mAh the battery lasts months.

### Protocol

For anyone building a different remote (it must match `RemoteButton.kt`):

- service `7e1a0001-5c3b-4f7e-9a2d-6b1f0c2e8a40`, included in the advertising
- characteristic `7e1a0002-5c3b-4f7e-9a2d-6b1f0c2e8a40`: notify, 1 byte, `1` = pressed, `0` = released
- standard Battery Service (`0x180F` / `0x2A19`), in percent

The app recognises clicks and long presses, so the firmware never needs to change.

## Case

`case/remote_case.scad` (OpenSCAD, parametric) and ready-made STLs:
`case/remote_case_base.stl`, `case/remote_case_lid.stl`.

Outer size: 39 × 25 × 16.4 mm (plus an 8 mm tab on each side for the strap).

**Printing**: PETG, 0.2 mm layers, 3 perimeters, no supports.
Print the base with the opening facing up and the lid with its top face on the bed
(the STLs are already oriented that way).

The dimensions come from datasheets: when the parts arrive, measure them (especially the
button cap and the battery thickness) and fix the parameters at the top of the file.
The most important one is `cap_protrude`, how far the cap stands out of the lid: the less it
protrudes, the fewer accidental presses (for example during push-ups).

### Assembly

1. Solder the button and the battery to the XIAO, flash the firmware and test everything outside the case.
2. In the base: battery on the floor, held with double-sided tape; on top of it, with thick foam tape,
   the XIAO with its USB-C against the opening in the short wall.
3. The button press-fits from below into the frame under the lid, with the cap coming out of the hole.
   Bend the pins outwards; if it wobbles, add a drop of hot glue.
4. Snap the lid on. To open it again, lever it with a screwdriver in the slot on the long side.
5. The velcro strap goes through the two slots from below.
