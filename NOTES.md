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
