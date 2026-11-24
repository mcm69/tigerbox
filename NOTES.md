# tigerbox TOUCH PLUS repurposing notes

Started 2026-11-06. Goal: repurpose the box (root / plain Android / own software).

## Hardware (documented for the plain TOUCH — PLUS not yet confirmed)

| Part | Value |
|---|---|
| SoC | Rockchip RK3128 (quad Cortex-A7, Mali-400 MP2) |
| RAM | 512 MB (2x 256 MB Samsung K4B2G1646F-BYMA DDR3L) |
| Storage | 8 GB eMMC |
| OS | Android 5.1 |
| Bluetooth | BK3266 BT audio SoC |
| Other | NFC reader (tigercards), audio amp, USB-C, no SD slot |

Evidence for RK312x: UART prompt `shell@rk312x`, partitions under
`/dev/block/platform/1021c000.rksdmmc/by-name/`. One source says "RK3218"
once and "RK3128" elsewhere — RK3128 is better supported by the evidence.

CAVEAT: neither upstream project mentions the TOUCH PLUS. Confirm on-device
via USB VID:PID + `getprop` + `/proc/cpuinfo` before trusting any of the above.

## Access route

Recovery mode (no disassembly, no bootloader unlock, no exploit):
1. Power off.
2. Paperclip into the RESET pinhole next to USB-C, hold.
3. Press HOME/power to switch on; release RESET after ~10 s.
4. Wait for Android Recovery, then connect USB-C.
5. `adb root` — works unrestricted in recovery.

`system` partition is `/dev/block/mmcblk0p12` on the TOUCH.

Persistent USB ADB in normal boot — set in `/system/build.prop`
(stock ships `persist.sys.usb.config=charging`):

    persist.service.adb.enable=1
    persist.service.debuggable=1
    persist.sys.usb.config=mtp,adb

Fallback: UART test pads on the PCB backside, 3.3 V, 115200 8N1, XON/XOFF,
TX/RX reversed, grey test pad = GND. Then just `su`. Requires prying the
case open over ribbon cables — last resort.

## Plan

1. Dump stock eMMC via `adb pull` BEFORE changing anything. 8 GB, slow.
2. Identify hardware for real (getprop, cpuinfo, partition table).
3. build.prop edit for persistent ADB.
4. Replace launcher (wiki has F-Droid + Aurora Store pages).

## Do NOT

- `adb push mmcblk0.img /dev/block/mmcblk0` from soptikopava's repo:
  full-device overwrite from an unverified binary, assumes the plain TOUCH's
  partition layout, destroys any chance of stock recovery.

## Sources

- https://github.com/tigerbox-reverse-engineering/tigerbox/wiki (Hardware Overview, UART-ADB-Root, Open-the-tigerbox)
- https://github.com/soptikopava/tigerboox-touch (image-overwrite approach — avoid)
- https://datasheet.lcsc.com/szlcsc/Rockchip-RK3128_C191248.pdf

## Status log

- 2026-11-06: device reportedly in recovery + cabled, but macOS sees ZERO USB
  devices (ioreg, system_profiler, lsusb, adb, fastboot all empty; no
  /dev/cu.* serial, no external disks).
- 2026-11-06: cable/port ruled out — a Pixel 10 Pro (18d1:4ee1) enumerated
  fine on the same cable and port.
- 2026-11-06: photo confirms the box IS sitting in recovery:

      Android system recovery <3e>
      R11.3.5-BTS84-20230818.101207
      Recovery system v5.0
      [reboot system now]
      [wipe data/factory reset]
      E:failed to mount /mnt/external_sd (No such device)   x3

  Still nothing on USB in this state.

## Key finding: this recovery does NOT expose adb

The menu has only TWO entries. Stock AOSP <3e> recovery normally also offers
"apply update from ADB" / "apply update from SD card" / "wipe cache", and on
AOSP it is selecting *apply update from ADB* that starts `adbd` (in sideload
mode). Those entries have been removed from this build, which is consistent
with `adbd` never starting in recovery — hence zero USB enumeration.

Firmware is dated 2023-08-18, i.e. AFTER the 2022 update that forum users say
locked things down. The wiki's "adb root in recovery" route was written for
the older TOUCH and does NOT apply to this device as it stands.

The repeated /mnt/external_sd mount failures mean recovery still has an SD
code path. The TOUCH is documented as having no SD slot; unknown whether the
PLUS has an internal one. Any update.zip would still need to pass OEM
signature checks, so this is unlikely to be a way in.

## UART adapter + wiring

Adapter: Okystar OKY3411, CP2102 6-pin (DTR / RXD / TXD / +5V / GND / 3V3).
Suitable: CP2102 drives its TX/RX at 3.3 V, matching the tigerbox UART.

macOS 27.0.1 has the driver built in (com.apple.DriverKit-AppleUSBSLCOM.dext)
- nothing to install. The adapter will appear as /dev/cu.usbserial-* or
/dev/cu.SLAB_USBtoUART.

Wiring - THREE wires only:

    adapter GND  ->  tigerbox GND   (grey-marked test pad)  [connect first]
    adapter RXD  ->  tigerbox TX
    adapter TXD  ->  tigerbox RX

DO NOT connect +5V or 3V3. The tigerbox powers itself; feeding it 5 V risks
damage. GND is the only power-rail connection.

If the console stays silent, swap RXD/TXD - the wiki explicitly notes the
labels are reversed relative to what you'd expect.

Connect with:

    screen /dev/cu.usbserial-0001 115200

(Exit screen: Ctrl-A then K, then y.) Settings: 115200 8N1, XON/XOFF.
Then `su` for root.

## Observed on the actual TOUCH PLUS board (2026-11-06, component side)

Confirmed by eye, this device:

- eMMC: Samsung KLM8G1GETF-B041 -> 8 GB. Matches the TOUCH.
- Wi-Fi/BT: separate blue daughter module, silkscreen "tigerbox TOUCH",
  "LBC-0509AF", dated 2023.08.15, with meander PCB antenna + u.FL ("WIFI1")
  to an external antenna. The TOUCH wiki lists a BK3266 BT audio SoC on the
  main board instead, so this part is laid out differently.
- Sonix MCU on the right edge, near the NFC pads.
- Labelled pads/connectors seen: SPKL, SPKR (speaker), NFC+ / NFC- (red
  twisted pair to the coil), "Touch" FPC connector, WIFI1 u.FL.
- SoC and RAM are NOT visible - they sit under the metal shield can in the
  middle of the board. Not yet confirmed as RK3128.
- No UART pads identified on this side. The wiki puts them on the BACKSIDE.

Conclusion: the PLUS board is a different layout from the TOUCH, so the
wiki's UART pad photo cannot be trusted for pad positions here. Identify
the pads on this board before soldering.

Fallback if no UART pads exist: the eMMC is a discrete BGA, so Rockchip
maskrom can be forced by shorting eMMC CLK or D0 to GND at power-on, which
would make the SoC fall back to USB (VID 2207) - then rkdeveloptool.
Riskier and needs the shield off.

### Second, clearer photo - parts now legible

Board silkscreen: **OTS178**, dated **23/06/12** (2023-06-12). A second
marking reads OTS332 on the opposite edge. Board date lines up with the
2023-08-18 firmware build.

| Marking | Part | Meaning |
|---|---|---|
| `Rockchip RK816B-1` | RK816 PMIC | **Confirms a Rockchip platform** |
| `SEC 325 B041 KLM8G1GETF NAPKB17PE` | Samsung eMMC | 8 GB |
| `REALTEK 8189FTV` | RTL8189FTV | SDIO Wi-Fi, feeds the WIFI u.FL |
| `FM17610` | Fudan NFC reader front-end | the tigercard reader |
| blue module `LBC-0509AF`, 2023.08.15 | BT audio module | separate daughterboard |

RK816 is the companion PMIC for the RK312x family, so RK3128 is now a
well-supported inference - but the SoC itself is still under the large
metal shield on the left half of the board and has NOT been read directly.

Labelled pads on this side: BATT (4-pad battery footprint), LOGO (2 pads,
likely the logo LED), SPKL, SPKR, WIFI (u.FL). **Still no UART pads on
this side.** The reverse side has not been photographed yet.

## Reverse side: UART pads FOUND - but there are THREE pairs

The solder side carries silkscreened TX/RX test pads in **three separate
places**, running roughly down the middle of the board:

1. upper pair, next to a column of unpopulated pads and a QFN
2. middle pair, isolated round pads
3. lower pair, near the bottom edge

Almost certainly: one is the **SoC console**, the others belong to the
Bluetooth audio module and the Sonix MCU. Only one will emit a kernel log.
No GND pad is silkscreened next to any of them.

Also on this side: the RESET tactile switch, a 4R7 inductor, and a blue
daughterboard marked `F-6986 V2.3 ROHS` along the top edge.

### Finding the right pair - LISTEN ONLY first

Do NOT connect the adapter's TXD yet. With only GND + the adapter's RXD
connected, nothing drives the board, so a wrong guess costs nothing.

    adapter GND  ->  board GND
    adapter RXD  ->  candidate TX pad
    (adapter TXD left disconnected)

GND source: the plated mounting-screw rings, or the shield can. Confirm
with a multimeter against battery negative before trusting it.

Then power on and watch each candidate in turn:

    screen /dev/cu.usbserial-0001 115200

The SoC console will dump a U-Boot + kernel boot log at power-on. If a pad
produces steady garbage rather than text, the pad is right but the baud is
wrong - try 1500000, which some Rockchip builds use. Silence = wrong pad.

Only once a readable boot log appears is it worth soldering TXD on to get
an interactive shell.

## UART console WORKS - boot log captured (2026-11-23)

Log archived at `logs/2026-11-23-uart-boot-partial.log`. 115200 8N1 was
correct. Confirmed directly from the device, no longer inference:

- **SoC is RK312x.** The audio driver is `sound/soc/codecs/rk312x_codec.c`
  and dozens of `rk312x_*` calls appear. RK3128 confirmed as the family.
- **PMIC is RK816** - `rk816_ldo4` appears as a regulator name.
- **Kernel 3.10.0 #3**, ARM, SMP (`CPU: 1` on a backtrace), module
  `drmboot(P)` - proprietary, so the kernel is tainted `P`.
- **Wi-Fi driver reports `RTL8189FS`**, while the silkscreen reads 8189FTV.
  Same family; the driver string is what matters for driver hunting.
- RAM pressure is severe: lowmemorykiller culls settings, keychain,
  keychain, acore etc. within 2 s of boot, "Free memory is -36kB above
  reserved". Consistent with 512 MB.
- RTC is unset - audit timestamps decode to 2015-01-21.

### Two findings that matter for the plan

**1. SELinux is PERMISSIVE.** Every `avc: denied` line ends with
`permissive=1`, so denials are logged but not enforced. Nothing in SELinux
will block remounting /system or editing build.prop.

**2. The tigerbox app itself owns a serial port.** pid 812 `.tiger.tigerbox`
opens, reads, writes and ioctls `/dev/ttyS0`. That accounts for one of the
three TX/RX pad pairs on the solder side - it is the app's link to a
peripheral (most likely the BT audio module), NOT the console.

Also seen: `lbc_pwm_control` / `flash_on` / `flash_off` / `led_store` drive
the logo LED (the `LOGO` pads on the component side), and `ble_store`
toggling `rk816_ldo4` throws a harmless unbalanced-regulator WARNING.

USB note: `dwc_otg_hcd_resume, usb device mode` then `_suspend` at ~12.5 s,
plus `acc_open`/`acc_release`. The USB gadget hardware DOES come up in
normal boot - it just has no functions bound, which is exactly what
`persist.sys.usb.config=charging` does. That supports the build.prop plan.

### Still needed

- This capture starts at **9.9 s**, so U-Boot and the early kernel are
  missing. Recapture from power-on to get `Linux version`, the kernel
  command line and the partition table - needed to know what to dump.
- Console is so far **receive-only**. Whether it gives an interactive shell
  is untested: that needs the adapter's TXD wired to the console RX pad.

## Full boot log captured from power-on (2026-11-23)

Archived: `logs/2026-11-23-uart-boot-full.log`. Partition table extracted
to `logs/partitions.md`. Hard facts now in hand:

- **SoC: RK3128, confirmed by name** - `Machine: Rockchip RK3128,
  model: rockchip,rk3128`; `CPU: rk3128` in U-Boot; ARMv7 [410fc075] x4.
- **RAM: 512 MB DDR3**, 32-bit bus (DDR init header + `Memory: 512MB`).
- **U-Boot 2014.10-RK3128** (built 2026-07-15); DDR blob "Boot1" 2019;
  "#Boot ver: 2023-08-18#2.31" matches the recovery build date.
- eMMC user area **7456 MB** (8 GB class).
- **androidboot.selinux=permissive** in the cmdline - confirms the earlier
  avc observation at the kernel level.
- console = **ttyFIQ0** (see below).
- Device serial: `REDACTED_SERIAL`.

### CONSTRAINT: secure boot is enabled AND locked

U-Boot prints:

    Secure Boot Mode: 0x1
    SecureBootEn = 1, SecureBootLock = 1
    Secure Boot state: 1

Meaning the boot ROM / loader verify signatures on the loader and boot
chain, and the lock fuse is blown. Practical consequences:

- Flashing an UNSIGNED custom uboot / kernel / boot.img (via maskrom +
  rkdeveloptool, or an update.zip) will be REJECTED. Full custom-ROM or
  mainline-Linux flashing is effectively gated without tigermedia's keys.
- The maskrom fallback (shorting eMMC) can still READ the eMMC for a dump,
  but cannot write a self-signed boot chain.
- What IS still open: reading/dumping all partitions, and modifying the
  CONTENTS of system/userdata (root, build.prop, launcher) - these are not
  signature-checked at boot on this platform (no dm-verity flag in the
  cmdline; the TOUCH's build.prop edit route relied on this). VERIFY there
  is no dm-verity on /system before trusting a system edit to still boot.

So realistic goal = rooted + reskinned stock Android, NOT a from-scratch OS.

### The console is the FIQ debugger, not a getty

cmdline: `console=ttyFIQ0 androidboot.console=ttyFIQ0`. At kernel start the
log prints `<hit enter to activate fiq debugger>`. So pressing Enter on the
soldered TX line brings up the Rockchip FIQ debugger, not a login shell
directly. From the fiq prompt, the `console` command switches to the kernel
console/shell. This is the interactive path once TXD is wired - expect the
fiq debugger first, then type `console`, then `su` (SELinux permissive).

### Partition numbering differs from the TOUCH

system is **mmcblk0p11** here (it was p12 on the TOUCH); p12 is
radical_update. Always resolve via
/dev/block/platform/*/by-name/, never by hardcoded pN.

### Dump plan (unchanged, now with specifics)

Once the interactive shell is up:
- full image: `dd if=/dev/block/mmcblk0 of=/data/.../full.img bs=4M` (8 GB)
  or pull per-partition by-name. system is 600M, userdata 6488M.
- `adb pull` is easier than dd-over-serial once USB ADB is enabled.

## ADB over USB WORKS (2026-11-23)

Root shell via UART (`su` -> uid=0, context u:r:su:s0), then
`setprop sys.usb.config mtp,adb` (live, not yet persisted). Box shows as
`REDACTED_SERIAL device ... product:rk312x`. Root-shell session
archived: `logs/2026-11-23-root-shell-session.log`.

Gotchas hit on the way:
- After boot the console is already a shell (`shell@rk312x:/ $`); the
  fiq/`console` step was unnecessary.
- Kernel spam (`chrg no di->ac_in`) interleaves with typing; tame it with
  `echo 1 > /proc/sys/kernel/printk`.
- Right after the setprop the box went into a deep-sleep loop
  (`GPIO0_INTEN ... armoff ... sram`), which powers USB down.
  `echo usbdebug > /sys/power/wake_lock` keeps it awake (not persistent).
- **USB-C to USB-C does NOT work.** The MacBookPro18,1 is USB-C only, and
  the box never appeared on a C-to-C cable. What fixed it: a USB-C-to-A
  adapter on the Mac + an A-to-C data cable to the box. Most likely the
  box's USB-C port lacks the CC pull-down resistors, so a USB-C host never
  turns on VBUS; a USB-A port always supplies 5V. This probably also
  explains why nothing ever enumerated at the very start.

### What the running system reports

| Prop | Value |
|---|---|
| ro.build.display.id | R12.4.4-BTS84-20260715.130314 |
| ro.build.fingerprint | rockchip/rk312x/rk312x:5.1.1/LMY49F/...:userdebug/test-keys |
| Android | 5.1.1 (LMY49F) |
| ro.build.type / tags | **userdebug / test-keys** |
| ro.secure / ro.debuggable | 1 / **1** |
| ro.adb.secure | 0 (no RSA authorization needed) |
| persist.sys.usb.config | charging (live test not yet persisted) |

- System was OTA-updated to a **July 2026** build; recovery is still the
  2023 one. Kernel and U-Boot build dates (Jul 13-15 2026) match.
- **userdebug + ro.debuggable=1** means `adb root` should restart adbd as
  root - no UART or su needed for future sessions.
- **test-keys** is only a build tag here. CORRECTED 2026-11-23: the
  platform certificate (SHA-256 2d370c21...) is NOT AOSP's public test
  key (C8:A2:E9:BC...), just a private key with the AOSP name copied onto
  it. We cannot sign apps as system; root + writable /system cover that.
- `/system` is **already mounted rw** (ext4). No remount, no dm-verity.
- su: `/system/xbin/su`, setuid root, group shell (mode rwsr-x---).
- by-name symlinks match logs/partitions.md exactly; system = mmcblk0p11.
- Usage: /system 291M of 581M; /data only 99M of 6.1G - nearly factory
  fresh, so a userdata dump will be mostly empty and compress well.

### Persisted and verified across reboot

    adb shell su 0 setprop persist.sys.usb.config mtp,adb
    adb shell su 0 setprop persist.service.adb.enable 1

Setting persist.sys.usb.config re-enumerates USB and kills the running adb
command mid-way - re-check and re-run anything after it. Both props land in
/data/property/. After `adb reboot` the box was back on ADB in 16 s with
sys.usb.config=mtp,adb, boot_completed=1.

`adb root` works (ro.debuggable=1): adbd restarts as uid=0, context
u:r:su:s0. Not persistent - run it once per boot. **UART is no longer
needed**; the probes can come off.

AOSP su syntax on this build is `su 0 <cmd>`, not `su -c`.

### Next

1. Dump via `adb exec-out dd if=/dev/block/mmcblk0 bs=4M` into dump/
   (gitignored). Mac has 303G free. Expect ~7.5 GB raw.

## DECISION (2026-11-23): DIY offline audio box with our own frontend

Keep the form factor, replace the tigermedia software: tap an NFC card ->
play local audio. No cloud, no subscription. Our own app becomes the HOME
launcher; the OEM apps get disabled (`pm disable`, reversible), not deleted.

### Unknowns to resolve first (investigation, after the dump)

| # | Question | How to find out |
|---|---|---|
| 1 | How does the NFC reader (FM17610) reach Android? | `pm list features` for android.hardware.nfc; else the stock app's `/dev/ttyS0` traffic. Decompile the tigerbox APK (from the dump) with jadx to read the card protocol. Tap a card while watching `logcat`. |
| 2 | What do the buttons send? | `getevent -l` while pressing the big HOME button (and any others) |
| 3 | How is the LED ring driven? | find the sysfs nodes behind `led_store` / `lbc_pwm` in the kernel log; `ls /sys/class/leds` |
| 4 | Which package is the OTA updater? | `pm list packages -f`, look for the one writing radical_update |
| 5 | Screen size/density | `wm size`, `wm density` |

### Build plan

- Native Android app, **minSdk/targetSdk 22**, plain Views (no Compose,
  no WebView) - the box has 512 MB RAM and an old WebView.
- Registers as HOME (kiosk): big cover art, play/pause, next, volume.
- Card registry: UID -> folder/playlist, a JSON file on /sdcard; a "learn
  card" mode to bind a new card to a folder.
- Audio from /sdcard (6 GB free on /data).
- Install as a user app first; move to /system/priv-app only if it needs
  privileges. (Signing with the AOSP platform test key does NOT work: the
  box's platform cert is vendor-specific - see SURVEY.md section 6.)
- **Big button (decided 2026-11-24): remap to play/pause; the app powers
  off.** Add `/system/usr/keylayout/rk816_pwrkey.kl` with
  `key 116 MEDIA_PLAY_PAUSE` (5.1 rejects the `WAKE` flag). Tested live
  2026-11-24, see SURVEY.md section 4. Short press = play/pause (the app
  must use a MediaSession to get it while the screen is off);
  hold ~3 s = app shuts the box down; plus an idle auto-off timer. The app
  then needs `android.permission.SHUTDOWN`, so it lives in
  `/system/priv-app`. Power-ON is unaffected: the RK816 PMIC switches the
  box on in hardware before Android runs. Fallbacks: holding the button
  ~6-8 s cuts power in the PMIC; `adb shell reboot -p`; deleting the .kl
  file restores the stock power key. The file is NOT on the box now.

### Order of work

1. Finish + verify dump (safety net).
2. Disable the OTA updater BEFORE the box goes back on Wi-Fi.
3. Resolve unknowns 1-5.
4. Toolchain on the Mac, then build the app.

### Mac toolchain (installed 2026-11-23)

| Tool | Version | Where |
|---|---|---|
| JDK | 21.0.13 (keg-only) | /opt/homebrew/opt/openjdk@21 - use as JAVA_HOME for builds; system default stays Temurin 26 |
| Gradle | 9.9.2 | brew |
| jadx / apktool | 1.5.7 / 3.0.3 | brew |
| Android SDK root | - | /opt/homebrew/share/android-commandlinetools |
| platform / build-tools | android-36 / 36.1.0 | (compile against 36, minSdk/targetSdk 22) |
| platform-tools | 37.0.3 | adb is also on PATH from brew |

Only `android-sdk-license` is accepted (approved 2026-11-23). sdkmanager's
`--licenses` accepts every listed license at once; the extras were removed
afterwards. Accept others individually if a future package needs them.

## eMMC backup DONE and verified (2026-11-23)

`dump/mmcblk0.img`, 7.8 GB, gitignored. 13 of 14 regions are byte-identical
to the live device; userdata differs only because /data is live. Details
and the CORRECTED partition table in logs/partitions.md.

**Correction:** the partition offsets first derived from the kernel
cmdline were 4 MB too low. Real starts come from
/sys/block/mmcblk0/mmcblk0pN/start; logs/partitions.md is now fixed.

## Device survey (2026-11-23)

Full read-only survey in **SURVEY.md**: memory and what to disable, music
storage, the card-reader protocol, buttons, LEDs, screen, and the update
path. Raw evidence in survey/raw/.

## App build: M0 done (2026-11-24)

The app lives in `audiobox/` (spec: APP-SPEC.md). Plain Java, no AndroidX or
Kotlin runtime, AGP 9.5.2 + Gradle 9.9.2 wrapper, compileSdk 36 with the
installed build-tools 36.1.0, min/target API 22. Build and test with
`JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew testDebugUnitTest assembleDebug`.

M0 verified on the box: installed as a user app (`adb install -r`), it scans
`/sdcard/audiobox/library/`, merges `library.json` (label, order, hidden)
and shows the grid with icon.png, or a letter tile as the fallback. The
`dev.audiobox.RELOAD` broadcast rescans. 8.9 MB PSS. Sample playlists made
from `say` speech (no copyrighted audio) are on the box: animals, bedtime,
counting (12 tracks, natural order), secret (hidden).

## M1 done (2026-11-24): playback, resume, button path

- `PlayerService` (foreground, always running) plays playlists with
  `MediaPlayer` (Rockchip's RockFFPlayer underneath), holds audio focus, and
  keeps a `MediaSession` active even when idle so the button always has a
  target. Resume per playlist in `state.json` (by file name + index),
  rewinds at the end of a playlist. Verified: plays through, survives
  `am force-stop` and resumes at the same track.
- **Keymap installed for good** (`/system/usr/keylayout/rk816_pwrkey.kl`,
  `key 116 MEDIA_PLAY_PAUSE`). Until the app is in /system/priv-app (M5) the
  ~3 s hold can't shut down (SHUTDOWN permission refused, logged); the PMIC
  6-8 s hold and `adb shell reboot -p` still work.
- **Testing the button without a finger:** `input keyevent 85` does NOT
  follow the real path (injected keys reach the window even with the screen
  off). Raw kernel events do:
  `sendevent /dev/input/event1 1 116 1; sendevent /dev/input/event1 0 0 0`
  then the same with `0` for release. Verified with the screen off: the
  press arrives at the MediaSession, the app wakes the screen, the release
  arrives at the activity, and the shared handler times it (short press =
  toggle, 3.5 s = power-off path).

## M2 done (2026-11-24): kid UI, button, lights

- Grid: 4 x 2 tiles per page, sideways swipe between pages with page dots,
  the loaded playlist ringed (plus a play badge while playing), battery in
  the corner. Player: big cover, label, track dots (or "3 / 12"), progress,
  prev / play-pause / next, volume -/+ with a bar (step 5 of 100), home
  button and swipe-right back to the grid. Verified on the box with 10
  playlists; 13.3 MB PSS.
- Swipes are detected in `dispatchTouchEvent`, because tiles consume
  their own touches.
- Lights follow the player (green steady / breathing, head dim / on,
  yellow on low battery, red while the button is held). NB: the stock
  tigerbox app still runs and drives the same LEDs until it's disabled in
  M5, so what you see now is a mix of both.
- **Font:** `Ui.font()` loads `assets/fonts/fredoka.ttf` (added 2026-11-24,
  see the M5 section) and falls back to the system bold sans.

## M3 done (2026-11-24): admin screen and parental controls

- **Admin:** hold the grid's top-left corner 5 s, then the PIN (own keypad;
  no PIN set = opens directly, with a "Set PIN" button). Playlists:
  reorder, resume / shuffle / bedtime / hidden toggles (renaming is a Mac
  tool job: the on-screen keyboard will be disabled). Volume caps, sleep
  default, idle auto-off minutes + "also while charging", bedtime window,
  clock, PIN, system info, a way out to Android settings. Saves JSON and
  sends RELOAD.
- **Verified on the box:** speaker cap 30 % lowered the volume from 40 at
  once; bedtime dims non-bedtime tiles and refuses them (overlay + red
  ring flash); moon button cycles 15/30/45/off and shows the minutes;
  sleep timer fades over 10 s and pauses mid-track (14.4 s into a 25.6 s
  track); idle auto-off fires from AlarmManager, stays on while charging
  by default, and with "also while charging" reaches the shutdown call;
  PIN set twice, wrong PIN refused, right PIN opens admin.
- Needs priv-app (M5) to take effect: shutdown (idle auto-off and button
  hold) and setting the clock from the admin screen. Until then the Mac
  tool sets the clock.
- Test leftovers removed: settings.json deleted (= defaults, no PIN).

## M4 done (2026-11-24): Mac tool `tools/audiobox`

Python 3 over adb: `status`, `push DIR [ID] [--label --icon --no-resume
--shuffle --bedtime --hidden --transcode]`, `list`, `set ID key=value`,
`rm ID` (asks), `time`, `pin-reset`, `install APK` (priv-app, for M5),
`reload`. JSON edits are pushed to a temp name and renamed on the box.
Verified on the box: push with AIFF->AAC conversion and a separate icon,
re-push skips unchanged files, set, list, rm, pin-reset, time.

Findings:
- Android 5.1's adb shell doesn't return exit codes; the tool echoes `$?`.
- busybox here has no `stat`, `date` or `hwclock`; toolbox `ls -l` and
  `df` are used instead.
- **Toolbox `date -s` ignores `-u`** and takes local time in the box's
  timezone; it sets the RTC too (checked via
  /sys/class/rtc/rtc0/since_epoch). **The box clock is now correct**
  (it read 2015-01-21 before).
- The box's timezone is Europe/Amsterdam (UTC+1) while the Mac is on
  UTC+2. Bedtime follows the box's timezone; left as is for now.
- afconvert: no fixed `-b` bitrate (128 kbps fails on `say`'s 22 kHz mono).

## M5 done (2026-11-24): system app, HOME, stock app off

- `audiobox install` put the app in `/system/priv-app/audiobox`: flags
  SYSTEM + PRIVILEGED, `SHUTDOWN` and `SET_TIME` granted. The manifest
  now registers `HomeActivity` as HOME (+ DEFAULT).
- `audiobox lockdown` (new; `--undo` reverses it) disables the updater,
  NFC, managed provisioning, mediafloat and LatinIME, and switches off
  the stock app *component by component*.
- **Trap found: the stock app can't be disabled as a package.** Rockchip/
  tigerbox patched `DevicePolicyManagerService.systemReady()` with a
  `silentlyActivateDeviceAdminIfNeeded()` that hard-codes
  `media.tiger.tigerbox/.TigerBoxDeviceAdminReceiver` and calls
  `findAdmin()`, which throws "Unknown admin" when the receiver doesn't
  resolve. After `pm disable media.tiger.tigerbox`, system_server crashed
  at boot phase 480 and restarted every ~9 s (soft boot loop; adb root
  still worked). Removing the APK would do the same. Recovered by `stop`,
  editing `/data/system/users/0/package-restrictions.xml` (package
  enabled, 15 components disabled, admin receiver left on), `start`.
  Now logs "Admin already active" and the stock process never starts.
  **If a boot loop ever recurs: check logcat for "Unknown admin".**
- Clean cold boot: system_server starts once, `boot_completed` within
  seconds of adb, audiobox focused, no LMK kills, no FATAL in logcat.
  PSS: system 39 MB, systemui 29 MB, audiobox 14 MB; ~110 MB free plus
  ~190 MB cache (was: stock app 71 MB + kills at boot).
- `com.android.nfc` still runs (8.6 MB) although disabled: 5.1 starts
  persistent apps regardless. Harmless; `pm hide` could be tried if RAM
  ever gets tight.
- Clock set from the admin screen works now (moved the hour and back).
- Big-button hold powers off: raw key down via `sendevent`, "big button
  held: powering off" exactly 3.0 s later, `ShutdownThread` starts ~0.5 s
  after that (resume saved first), clean low-level shutdown.
- The stock app no longer drives the LEDs, so the ring and head are ours.
- Not handled yet: the stock app listened for `com.android.bts84.Abnormal_current`
  (an over-current safeguard broadcast from the board's service). Its
  receiver is now off; audiobox should take over that broadcast later
  (unknown what the stock app did with it).
- Still to do: a day of use with no LMK kills (spec's "done when"); the
  optional `install-recovery.sh` for `/dev/i2c-2` (bottom light).
- Font added (OK given 2026-11-24): `Fredoka[wdth,wght].ttf` + `OFL.txt`
  from google/fonts `ofl/fredoka` (no Reserved Font Name), cut to a static
  SemiBold (wght 600, wdth 100) with fontTools' instancer because API 22
  can't select a weight from a variable font; 50 KB. Shipped as
  `assets/fonts/fredoka.ttf` with `OFL.txt` next to it. Letter tiles use it
  too. Glyphs it lacks (← ✕ ● ○) fall back to the system font, checked on
  the box. A cold power-on from off also booted cleanly into audiobox.
- Way back from Android settings (2026-11-24): the box has no navigation
  bar (the window exists but is hidden) and the big button is play/pause,
  so nothing on the device leads home. `PlayerService` now brings
  `HomeActivity` to the front on `SCREEN_OFF`; tested Settings -> screen
  off -> grid. From the Mac: `adb shell input keyevent 3`. (`dumpsys
  activity`'s `mFocusedActivity` line can lag; `mResumedActivity` and
  `mFocusedStack` are the ones to trust.)
- Timezone (2026-11-24): box set to Europe/Tallinn (`setprop
  persist.sys.timezone`, applied by a reboot) to match the Mac, then the
  clock set with `tools/audiobox time`; RTC agrees. Bedtime now follows
  Tallinn time. The clock had been ~1 h fast after a visit to Android's
  Date & time screen.
- Font switched to Nunito (2026-11-24): Fredoka has no Cyrillic (subsets
  latin, latin-ext, hebrew), so Cyrillic labels fell back to the thinner
  system font. Nunito (google/fonts `ofl/nunito`, OFL, no Reserved Font
  Name; subsets incl. cyrillic and cyrillic-ext) cut to a static
  ExtraBold (wght 800) to keep the chunky look; 132 KB, shipped as
  `assets/fonts/nunito.ttf` with its `OFL.txt`. Checked on the box with
  the label "Ёжик Їжак" on the grid, the letter tile and the player.
- Renaming the loaded playlist now updates the player screen at once:
  on RELOAD the service swaps in the re-read playlist when its tracks are
  unchanged (play order and position stay valid; playback isn't
  interrupted). Changed tracks still apply on the next load.
- `tools/audiobox set ID label=` (empty) now removes the label, so the app
  falls back to the folder name.
