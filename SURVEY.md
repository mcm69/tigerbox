# tigerbox TOUCH PLUS — device survey

Read-only survey of the running box, 2026-11-23, taken after the full eMMC
backup was verified. Nothing on the device was changed. Raw command output
lives in [`survey/raw/`](survey/raw/); the vendor APKs and decompiled code
are in `survey/apks/` and `survey/decompiled/` (gitignored, copyrighted).

**Headlines**

- **RAM is fine once the tigerbox app is gone.** It alone is 70.7 MB of the
  167 MB that apps use. Replacing it and disabling four other packages
  should take the out-of-memory killer from "fires at every boot" to
  "should never fire".
- **~5.5 GB is usable for music** — roughly 95 hours at 128 kbps.
- **The card reader is easy to use from our own app**: a world-readable
  device file plus two broadcasts. No root or special permission needed.
  Confirmed live with a transit card.
- **The big button is the power key**, so it can't be a plain play/pause
  key without more work (section 4).
- **Updates come from the tigerbox app + RKUpdateService together.**
  The box has no Wi-Fi network saved, so nothing can arrive right now.
- **We can't sign apps with the platform key** — it isn't AOSP's public
  test key after all. Not a blocker: root + writable `/system` cover it.

---

## 1. Memory

### Budget

| | |
|---|---|
| Physical RAM | 512 MB |
| Visible to Linux (`MemTotal`) | 492 MB (503,888 kB) |
| Kernel, incl. slab | 62 MB |
| Reserved / "lost" (U-Boot logo 32 MB, CMA 24 MB, GPU/ion) | 35 MB |
| **Left for apps** | **~395 MB** |
| Used by apps now (total PSS) | 167 MB |
| zram swap | 64 MB, 10.5 MB in use |

(`Free RAM` reads 240 MB at the moment, but 206 MB of that is block-device
cache left over from the backup verification — reclaimable, not "free" in
the usual sense.)

### When the out-of-memory killer fires

The thresholds are write-only in sysfs, but the kernel prints them in every
kill message, and ActivityManager reports the top one (`oom 103991 kB`):

| Kills processes with oom_score_adj ≥ | when free + file cache drops below |
|---|---|
| 1000 (empty / cached apps) | 101.5 MB (103,988 kB) |
| 529 (background apps) | 83.4 MB (85,384 kB) |

### What it killed this boot

Five kills, **all within 2 s of boot, none since** (uptime ~58 min). All
were cached or background processes started by boot-time receivers:

`android.process.acore` · `com.android.externalstorage` ·
`com.android.keychain` · `com.android.managedprovisioning` ·
`com.android.settings`

Killing cached processes is Android working as designed, not a crash — but
it shows how little headroom there is while everything starts at once.

### Who uses the memory (PSS)

| Process | PSS | Notes |
|---|---:|---|
| `media.tiger.tigerbox` | **70.7 MB** | the stock app; foreground, HOME |
| `system_server` | 32.1 MB | Android itself — keep |
| `com.android.systemui` | 20.4 MB | status/nav bar, volume UI — keep (see below) |
| native daemons (zygote, surfaceflinger, mediaserver, …) | 12.9 MB | keep |
| `com.android.inputmethod.latin` | 10.0 MB | on-screen keyboard |
| `android.process.media` | 8.2 MB | MediaProvider — keep |
| `android.rockchip.update.service` | 6.6 MB | the OTA installer |
| `com.android.nfc` | 5.7 MB | stock NFC stack — **unused**, state `off` |

### What to turn off

Disabling only saves RAM for packages that actually *run* — a disabled app
that never starts saves nothing. So the list is short:

| Package | Saves | Verdict | Why |
|---|---:|---|---|
| `media.tiger.tigerbox` | 70.7 MB | **disable** — *after* our launcher is installed | it is the current HOME app; disabling it first leaves the box with no home screen |
| `android.rockchip.update.service` | 6.6 MB | **disable** | installs OTA updates (section 5) |
| `com.android.inputmethod.latin` | 10.0 MB | **disable** — after Wi-Fi is set up, if ever | the kiosk needs no keyboard, but typing a Wi-Fi password does |
| `com.android.nfc` | 5.7 MB | **disable, then check** | not used: the card reader bypasses it entirely (section 3). Persistent system app, so confirm nothing complains |
| `com.android.managedprovisioning` | boot spike | **disable** | enterprise provisioning; one of this boot's kills |
| `com.android.rk.mediafloat` | boot spike | **disable** | Rockchip floating-media overlay; has a boot receiver |
| `com.android.systemui` | 20.4 MB | **keep for now** | disabling it is possible but brittle (volume dialog, keyguard live here). Only revisit if RAM is still tight |
| `com.android.settings` | — | **keep** | killed at boot, harmless; needed for Wi-Fi setup |

Leaving everything else alone, including the live wallpapers and webview
(never running, so they cost nothing).

### Expected result

| | PSS |
|---|---:|
| Now | 167 MB |
| minus tigerbox, keyboard, updater, NFC | −93 MB |
| plus our app (plain Views + MediaPlayer, estimate) | +15–25 MB |
| **After** | **~90–100 MB** |

That leaves roughly 300 MB of free + cache against a 101.5 MB threshold,
so the killer should never fire in normal use. Boot is the tighter moment;
removing the 70 MB app and the boot receivers should keep it clear too.
**This is an estimate — confirm after the change by rebooting and checking
`dmesg | grep lowmemorykiller` is empty.**

---

## 2. Storage for music

| Mount | Size | Used | Free |
|---|---:|---:|---:|
| `/data` (and `/sdcard`, emulated on top of it) | 6.1 GB | 99 MB | **6.0 GB** |
| `/system` | 581 MB | 291 MB | 290 MB (not for music) |
| `/cache` | 122 MB | ~0 | 122 MB (not for music) |

`/sdcard` is empty apart from the standard folders and 324 kB of tigerbox
logs. Keeping ~0.5 GB back for the system, logs and our app leaves
**~5.5 GB for audio**:

| Bitrate | Hours |
|---|---:|
| 64 kbps (audiobooks, spoken word) | ~190 h |
| 128 kbps | ~95 h |
| 192 kbps | ~65 h |
| 256 kbps | ~48 h |

- **No SD card or USB stick.** The software is configured for an SD slot
  and USB storage (vold, recovery), but the board has no slot, and the
  USB-C port almost certainly can't act as a host (section 6).
- **Copying:** `adb push` ran at ~3 MB/s for the backup, so expect
  ~5–6 minutes per GB, ~30 minutes to fill it. MTP is also enabled.
- **Never put `update.zip` or `update.img` in the top level of `/sdcard`.**
  RKUpdateService scans for exactly those names (section 5).

---

## 3. The card reader

The FM17610 is driven by a custom kernel driver, `mf175x`, on I2C bus 2
at 0x16. Android's own NFC stack (`com.android.nfc`, `NfcNci`) is
installed but switched off and plays no part.

**How the stock app reads a card**
(`HardwareNfcService`, decompiled):

1. The reader produces a key event; Rockchip's patched key handler
   (`PhoneWindowManager` in `android.policy.jar`) turns it into a broadcast:
   - **`com.android.bts84.nfcin`** — card placed
   - **`com.android.bts84.nfcout`** — card removed
2. On `nfcin`, the app reads **106 bytes from `/dev/mf175x`**.
3. The bytes, as hex:
   - **start:** an NDEF URI record — byte 4 is the payload length, the URL
     runs from byte 7 (`https://` prefix). Tiger cards carry a URL; the
     app's only backend is `tgrmd.de`.
   - **last 10 bytes:** the UID with framing bytes. Dropping hex
     positions 0, 1, 8, 9, 18, 19 leaves the **7-byte UID** (14 hex chars).

**For our app this is all good news:**

- `/dev/mf175x` is mode **0777** (`chmod 777` in `init.rc`) — any app can
  read it.
- Broadcasts are plain, unprotected intents — any app can receive them.
- We only need the UID. Any NTAG213/215 sticker or card has one, so cards
  map **UID → folder** with no data written to the card.

### Confirmed live (2026-11-23, with a transit card)

Captures in [`survey/raw/live/`](survey/raw/live/).

| Card | Kernel events | Broadcast |
|---|---|---|
| placed | `KEY_KP1` on the reader + `KEY_WAKEUP` on the keypad, same instant | `com.android.bts84.nfcin` |
| lifted | `KEY_KP2` + `KEY_WAKEUP` | `com.android.bts84.nfcout` |

So the path works end to end. (On paper `KP1`/`KP2` map to
`NUMPAD_1`/`NUMPAD_2` while the handler checks other key codes; whatever
reconciles the two, it works, and our app only needs the broadcasts.)

**The UID frame, worked out from a real read.** The last 10 bytes are the
card's anticollision data:

| UID size | Last 10 bytes | UID |
|---|---|---|
| 4 bytes (transit card, MIFARE-type fobs) | `U0 U1 U2 U3 BCC 00 00 00 00 00` | bytes 0–3 |
| 7 bytes (tiger cards, NTAG) | `88 U0 U1 U2 BCC0 U3 U4 U5 U6 BCC1` | bytes 1–3, 5–8 |

`BCC` is the XOR of the bytes before it (for 7-byte UIDs, BCC0 also covers
the `88` cascade tag) — it checked out on the transit card. The stock app's
"drop hex positions 0, 1, 8, 9, 18, 19" rule is just the 7-byte case and
mangles 4-byte UIDs. **Our parser: check for `88`, take the right bytes,
verify the BCC, reject an all-zero frame.**

The transit card's data area came back empty (`04` then zeros) — it isn't an
NTAG, so it carries no NDEF payload, and the stock app failed to parse it
(`StringIndexOutOfBoundsException`). Its UID is masked in the saved capture.

Two side observations:
- The card **wakes the box**: the reader raises `KEY_WAKEUP` alongside its
  own key. Placing a card can start playback from sleep.
- **SELinux logs the read as denied** (`/dev/mf175x` has the generic
  `device` label) but allows it, because it's permissive. Fine as long as
  the box stays permissive — and it's set on the kernel command line.

---

## 4. Buttons, lights, screen

### Buttons

Three input devices besides the touchscreen and the reader:

| Device | Layout file | Keys it can send |
|---|---|---|
| `rk29-keypad` (ADC buttons) | `rk29-keypad.kl` | HOME, VOLUME_UP, VOLUME_DOWN, POWER, WAKEUP, PAGE_UP, PAGE_DOWN, PICTSYMBOLS, SWITCH_CHARSET, F1–F3 |
| `rk816_pwrkey` (PMIC) | `Generic.kl` | POWER |
| `gslX680` | — | touchscreen |

The patched key handler turns several of these into broadcasts instead of
normal key events:

| Key | Broadcast |
|---|---|
| PAGE_DOWN | `short_down` |
| PAGE_UP | `long_down` |
| PICTSYMBOLS | `org.huiyu.honeybot.action.ReadButton` |
| SWITCH_CHARSET | `org.huiyu.honeybot.action.RecButtonDown` / `…RecButtonUp` |
| MEDIA_RECORD | `com.android.bts84.Abnormal_current` (current-fault alarm) |

**Confirmed live: the big tiger button is the power key.** It's wired to
the PMIC (`rk816_pwrkey`, `/dev/input/event1`) and sends plain
`KEY_POWER` — short and long presses alike; only the hold time differs
(0.07–0.21 s vs 2.7 s). My guess that it sends `short_down`/`long_down` was
wrong. The keypad's PAGE_UP / PAGE_DOWN / PICTSYMBOLS / SWITCH_CHARSET
entries have no physical button on the PLUS; they're leftovers from other
products on this platform (`honeybot` included). The only other button is
RESET, which we didn't press.

What that means for our app: Android handles POWER before any app sees it
— a short press toggles the screen (and the tigerbox app treats that as
sleep/wake, logging `RESTART FROM SLEEP - SHOULD TURN ON NFC AND BT`), a
long press opens the power-off menu. **The button can't be a plain
play/pause key without changing how POWER is handled.** Options, to decide
at build time: use the screen-on/off broadcasts it already causes; or use
the touchscreen for play/pause and leave the button as power. Changing
POWER itself would mean modifying `android.policy.jar` on `/system` — a
bigger, riskier change.

**Probably the best option:** the power key currently falls back to
`Generic.kl`, because there is no `rk816_pwrkey.kl`. Android prefers a
layout file named after the device, so adding
`/system/usr/keylayout/rk816_pwrkey.kl` with
`key 116 MEDIA_PLAY_PAUSE WAKE` would make the button a real play/pause key
that still wakes the box. Undoing it is deleting one file. The catch: the
box loses its software power key, so our app would have to provide
long-press → power off itself (`android.permission.SHUTDOWN`, which needs
the app in `/system/priv-app`). Untested — and needs a fallback plan before
trying it, since ADB stays available either way.

### Lights

All under `/sys/devices/pwmleds.20/`, mode 0666 (writable by any app).
Reads return garbage (a driver bug), so treat them as write-only. Meanings
come from the stock app's `LightControl`:

| File | Controls | Values |
|---|---|---|
| `modem_status` | **button ring** colour | `000000` off · `100000` yellow · `001000` green · `000010` red · `110000` / `001100` / `000011` same colours flickering |
| `breathled_switch` | button ring "breathing" | `7` yellow · `1` green · `5` red |
| `time_on`, `time_off` | flicker timing (ms) | stock uses 900 ms off |
| `laohutou_status` | the **tiger-head** light (*lǎohǔ tóu*, "tiger head") | brightness 0–71 |
| `/sys/bus/i2c/devices/i2c-2/2-0064/led_debug` | "music light" | `1` on · `0` off |

**Tested live 2026-11-23** (non-persistent sysfs writes, every write
accepted):

| Light | Result |
|---|---|
| Button ring, solid yellow / green / red / off | **works** |
| Button ring, flicker (`110000` / `001100` / `000011`, `time_on`/`time_off` 300 ms) | **works** — a blink, visibly different from breathing |
| Button ring, breathing (`breathled_switch` 7 / 1 / 5) | **works** — a smooth animation. Stopped by writing `0` |
| Tiger head, `laohutou_status` 0 → 71 → 0 | **works** — brightness changes in visible steps, not smoothly, so any fade has to be done by the app writing values over time |
| "Music light", `led_debug` 1 / 0 | **works — sound-reactive.** Nothing in silence; with `1` written and audio playing, the **bottom of the box flashes blue in sync** with the sound |

**Ring colour mixing** (tested live, photographed — see
[`survey/photos/ring/`](survey/photos/ring/), metadata stripped):

| `modem_status` | Bits set | Looks like |
|---|---|---|
| `001000` | green | green |
| `000010` | red | red |
| `100000` | yellow | amber/yellow |
| `001010` | green + red | **amber — a real blend, ≈ yellow** |
| `101000` | yellow + green | plain green |
| `100010` | yellow + red | plain red |
| `101010` | all three | amber, ≈ green + red |

Reading: the ring has **only red and green LEDs; "yellow" is both on**.
Setting green or red alongside yellow makes the driver drop the other LED,
so the explicit colour wins. LEDs are on/off only, with no brightness, so
the full palette is **off · green · red · amber**. No custom colours.
(Judged from phone photos with automatic white balance, so this is about
colour families, not exact shades.)

The music light is an **SN3199** (Si-En 9-channel I2C LED driver, i2c-2
0x64, driver `sn3199`). `led_debug` is a mode switch, not a brightness:
`1` enables the sound-reactive effect, `0` disables it. Tested with a quiet
6 s chime played through `tinyplay` with `tinymix "Playback Path" SPK`
(the path the audio HAL itself sets), path set back to `OFF` afterwards.
For our app: write `1` when playback starts if the effect is wanted —
the stock app defaults it on (`MUSIC_LIGHT_DEFAULT = true`).

#### SN3199 register map (from Si-En's datasheet, P0.6, Chinese)

Source: Si-En SN3199 datasheet (oneyac.com mirror), not stored in the repo.

- **I2C address** `0x64` (7-bit `11001` + AD pins; AD tied to GND → `00`).
  The chip is **write-only** — nothing can be read back. The kernel's
  regmap (`/sys/kernel/debug/regmap/2-0064/`) marks every register volatile
  and keeps no cache, so it can't tell us what `led_debug` writes either.
- Outputs form three RGB groups: **RGB1 = OUT1–3, RGB2 = OUT4–6,
  RGB3 = OUT7–9** (datasheet reference circuit — the box's wiring unknown).

| Reg | Name | Meaning |
|---|---|---|
| `00h` | shutdown | bit0: `0` software shutdown, `1` normal |
| `01h` | LED control 1 | bits 6–4 OUT6–4, bits 2–0 OUT3–1 on/off (default `0x77`) |
| `02h` | LED control 2 | bits 2–0 OUT9–7 on/off (default `0x07`) |
| `03h` | config 1 | bits 6–4 RGB3–1 mode (`0` PWM, `1` auto-breathing) · bit2 **AE audio-sync** · bit1 AGC off · bit0 AGC speed |
| `04h` | config 2 | bit7 cascade slave · bits 6–4 **current**: `000` 20, `001` 15, `010` 10, `011` 5, `100` 40, `101` 35, `110` 30, `111` 25 mA · bits 2–0 audio gain 0 to +21 dB in 3 dB steps |
| `05h` | breath hold | per group hold enable + hold at T2 or T4 |
| `06h` | breath marker | V_BM pin output (unused here) |
| `07h`–`0Fh` | **PWM OUT1–OUT9** | 256 levels each |
| `10h` | data update | write anything → applies `01h`, `02h`, `07h`–`0Fh` |
| `11h`–`19h` | T0 OUT1–9 | start delay |
| `1Ah`–`1Ch` | T1–T3 RGB1–3 | rise / hold / fall times |
| `1Dh`–`25h` | T4 OUT1–9 | off time |
| `26h` | time update | write anything → applies `11h`–`25h` |
| `FFh` | reset | write anything → all registers to power-on defaults |

So the chip can do **256-level colour per output and hardware breathing
per RGB group** — whether that means real colours depends on which outputs
have LEDs fitted.

**Output mapping, tested live 2026-11-23** (`tools/sn3199/map-outputs.sh`:
each of OUT1–OUT9 alone for 5 s at 5 mA, PWM 0x80, then all nine): blue
near the start of the sequence, nothing for most of it, then the same blue
but brighter with all nine on. **Only blue LEDs are fitted — no colour
mixing on the PLUS.** "Brighter with all nine" means more than one output
drives blue LEDs; the run's 5 s steps didn't separate exactly which ones
(somewhere in OUT1–OUT3, the first RGB group). Still available in blue:
256-level dimming, hardware breathing, audio sync.

**Confirmed with a second, slower run** (`tools/sn3199/blue-demo-device.sh`,
button ring flashing as a slot counter):

| Output | Result |
|---|---|
| **OUT1, OUT2, OUT3** (group RGB1) | **blue** |
| OUT4–OUT9 | nothing fitted |
| Software fade, PWM 0→255→0 on OUT1–3 | **smooth** — the 256 levels work, unlike the tiger head's steps |
| Hardware breathing, RGB1 in auto-breathing mode | **works**: T1/T3 = 2.08 s rise/fall (`0x1a`=`0x13`), T2 = 0.26 s hold, T4 = 0.52 s off (`0x1d`=`0x02`), peak = PWM, no CPU involved |

Gotcha found on that run: loading the peak PWM with `0x10` *before*
setting `0x03` to breathing mode lights the LEDs at full brightness for a
moment. Set the mode first, then timings, then PWM + `0x10`, then `0x26`
(the script is fixed; the fixed order is not yet re-tested on the box).

**For our app:** control the bottom glow through OUT1–OUT3 only (regs
`0x07`–`0x09`), as one blue channel. It needs the native helper (or the
same code as JNI) — Java can't issue the I2C ioctl.

Writing it needs a native helper: `/dev/i2c-2` exists, but the kernel
driver already owns address `0x64` (so the helper needs `I2C_SLAVE_FORCE`)
and there is no `i2cset` on the box.

Flicker timing was reset to `time_on` 100 / `time_off` 900 afterwards
(900 is the stock app's value, 100 is a guess) and the ring to green.

### Screen

| | |
|---|---|
| Resolution | **800 × 480**, landscape |
| Density | 160 dpi (mdpi), so 1 dp = 1 px |
| Panel rotation | `ro.sf.hwrotation=90` (handled by the system) |
| System language | German (`Integrierter Bildschirm`) |
| Screen timeout | 60 s; not kept awake on power |

### Power and sleep

- Battery 80 %, on AC power through the USB-A cable.
- **The suspend loop we hit earlier was the tigerbox app's doing.** Its
  `DefaultDeepSleepTimerService` calls `EngageDeepSleep.turnOffNfc` and
  puts the box into deep sleep on a timer. Our app needs its own policy:
  hold a partial wake lock while playing, and decide when to sleep.

---

## 5. How updates arrive

Two packages work together:

1. **`media.tiger.tigerbox`** (`UpdateCheckTimerReceiver`, `UpdateActivity`)
   checks `tgrmd.de`, downloads `downloaded_firmware.enc`, decrypts it
   with a key from its assets, and writes **`update.zip`** to shared
   storage.
2. **`android.rockchip.update.service`** (RKUpdateService, "System Update")
   watches `/data/media/0` (= `/sdcard`), `/mnt/external_sd` and
   `/mnt/usb_storage` for `update.zip` / `update.img`, writes the command
   to `/cache/recovery`, and reboots into recovery to install.
   It also polls `ro.product.ota.host = www.rockchip.com:2300`, Rockchip's
   generic default — probably inert.

The `radical_update` partition is commented out of the fstab and nothing
mounts it.

**Current risk: none.** Wi-Fi is switched on but no network is saved
(`wlan0` NO-CARRIER), and the box was factory-reset on 2026-11-06 — the
stock app is sitting on its onboarding screen. Disabling both packages
closes the path for good. Simplest of all: the audio box doesn't need
Wi-Fi, so it can stay off.

---

## 6. Platform facts that shape the build

- **Signing:** the tigerbox app runs as `android.uid.system` because it is
  signed with the platform certificate, SHA-256 `2d370c21…87d3522`.
  AOSP's public test platform key is `C8:A2:E9:BC…19:2A:B8`, so **this is
  a private key** with the AOSP name copied onto it. We can't make our app
  a system-UID app. Not needed: root + writable `/system` let us install it
  in `/system/priv-app` if it ever needs privileged permissions; as a plain
  user app it can already read the card reader and write the LEDs.
- **Target:** the stock app compiles against API 35 and targets API 22 —
  the same setup planned for ours.
- **USB host mode:** vold is set up for USB storage, but host mode on a
  USB-C port needs CC resistors, and we found the port behaves as if it
  has none. Don't plan on USB sticks.
- **The serial port** `/dev/ttyS0` (mode 0777) is the link to the
  Bluetooth daughterboard — confirmed, see section 7.

---

## 7. The rest of the hardware (checked 2026-11-23)

Read-only checks over ADB; raw output in `survey/raw/hw-*.txt`.

### What's actually on the buses

| Bus | Address | Part | Status |
|---|---|---|---|
| I2C-0 | `0x1a` | RK816 power chip (PMIC, battery, RTC, power key) | in use |
| I2C-0 | `0x1c`, `0x5b` | RK818, ACT8931 | declared, **not fitted** (reference-design alternatives) |
| I2C-2 | `0x16` | `mf175x` card reader | in use |
| I2C-2 | `0x28` | NXP `pn547` NFC | declared, **not fitted** |
| I2C-2 | `0x40` | `gslX680` touch | in use |
| I2C-2 | `0x64` | SN3199 LED driver | in use |
| SDIO | — | Realtek `0x024c:0xf179` (RTL8189FS Wi-Fi) | in use |
| eMMC | — | Samsung `8GTF4R` | in use |
| USB, SPI | — | nothing attached | |
| UART `ttyS0` | — | Bluetooth daughterboard | in use (stock app) |

The **Sonix chip** near the NFC pads is on none of these buses, so Linux
can't see it — standalone, or behind the Bluetooth module. The
**`F-6986 V2.3` board** on the solder side is very likely the back of the
Bluetooth daughterboard (`LBC-0509AF` on the front): a blue board with an
antenna overhanging the edge appears on both sides.

### Screen and touch

- Backlight `rk28_bl`: **0–225** (`/sys/class/backlight/rk28_bl/brightness`).
  Android's own setting is 102 of 255.
- Touch `gslX680`: multitouch, 0–800 × 0–480, matching the panel. Not
  tested by hand, but the stock app's onboarding screens work.

### Battery and power

- Full capacity **5000 mAh** (`rk816-bat: fcc=5000`).
- At boot (screen on, busy) it drew **~245 mA**; charging now at ~257 mA
  near full. One sample, but it points to well over a day of use per
  charge. Charging works from the USB-A cable (`ac: online=1`).
- The stock driver limits charge input to 450 mA when it sees no USB host.

### Clock

- **Working real-time clock** in the RK816 (`/sys/class/rtc/rtc0`), read
  into Android at boot (`hctosys=1`). It ticks, but it has **never been set**
  — the box thinks it's 2015-01-21 — because `auto_time=1` waits for a
  network that never comes. Timezone is already `Europe/Amsterdam`.
- Setting it once by hand would stick for as long as the battery has charge.
  Needed for anything time-based (sleep timers, bedtime mode).

### Audio

- One sound card, `RK_RK312X`: playback paths `SPK`, `HP`, `BT` and more
  (`tinymix "Playback Path"`), speaker amp enable on GPIO 30.
- **Stereo, probably**: the speaker amp is fed from both `HPOUTL` and
  `HPOUTR`, and the board has `SPKL` / `SPKR` pads. Confirming two speakers
  needs a left/right test with someone listening.
- **Headphone detection exists** (`/sys/class/switch/h2w`, currently 0 =
  nothing plugged in). **The PLUS has a 3.5 mm headphone socket** (confirmed
  by eye 2026-11-23).
- **Microphone: unconfirmed.** Android lists a built-in mic, but that comes
  from config, and the kernel logs errors setting up mic bias. The only
  real test is a short recording — not done, as it captures room audio.

### Bluetooth

- **Android's Bluetooth stack is unused**: off, no address, and the only
  `rfkill` switches are Wi-Fi. The Realtek Bluetooth library on `/system`
  is a reference-build leftover; the RTL8189FS here is Wi-Fi-only.
- Bluetooth is **the daughterboard, driven by the stock app over
  `/dev/ttyS0` at 115200 baud** (`com.yf.huaers.serial485.imp.SRBluetoothService`,
  wrapped by `com.tigermedia.bt.BtSerial485Service`).
- It's a **Bluetooth audio *source***: the box sends its sound to
  headphones or speakers (the app's "sound destination" switch, and the
  codec's `BT` playback path), and the headphones' buttons come back as
  events.
- The protocol is small — about 30 byte codes in `SRConstants`: search,
  connect, reconnect with a stored link key, delete pairing; status back
  (on/off, connected, disconnected, connect failed, search ended, play,
  pause) and button events (play/pause, stop, next, previous, volume ±).
  Reimplementable if Bluetooth headphones are wanted; not needed otherwise.

### Not present

- **Camera**: Android reports 0 camera devices. **GPS**: no device, no
  provider. Both features are flags inherited from Rockchip's reference
  build.

---

## 8. Suggested order of work

Nothing below has been done; it's the plan this survey supports.

1. ~~Live checks with you present~~ — done 2026-11-23: card path confirmed,
   button is POWER. Still to do with real NTAG stickers: one tap to see a
   7-byte UID and NDEF data.
2. **Build our launcher app** and install it. Decide first how the button
   should behave (section 4).
3. **Make it the HOME app**, then disable `media.tiger.tigerbox`.
4. Disable `android.rockchip.update.service`, `com.android.nfc`,
   `com.android.managedprovisioning`, `com.android.rk.mediafloat`, and
   `com.android.inputmethod.latin` (unless Wi-Fi is wanted).
5. Reboot and verify: no `lowmemorykiller` lines in `dmesg`, our app on
   screen, cards and buttons working.
6. Copy music in, map cards.

All disables use `pm disable`, so each one is undone with `pm enable`, and
the verified full backup covers anything worse.
