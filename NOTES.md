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
  /dev/cu.* serial, no external disks). Suspect charge-only cable or recovery
  timeout. Not yet reproduced after one reconnect attempt.
