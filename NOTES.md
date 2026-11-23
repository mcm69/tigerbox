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
