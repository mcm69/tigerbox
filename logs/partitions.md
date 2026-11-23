# eMMC partition table (RK3128 tigerbox TOUCH PLUS)

Authoritative layout read from the running kernel
(`/sys/block/mmcblk0/mmcblk0pN/{start,size}`), 512-byte sectors.
eMMC total: 15,269,888 sectors = 7,818,182,656 bytes.

**Every partition starts 8,192 sectors (4 MB) later than the `mtdparts=`
offsets in the kernel command line say.** Rockchip's legacy eMMC layout
treats those offsets as relative to a reserved 4 MB area at the start of
the disk. Sizes are as stated. Use the table below, not the cmdline.

Sectors 0-16383 (first 8 MB) hold the Rockchip ID block / loader and the
parameter area - only a full-disk image captures them.

| # | name | start (sct) | size (sct) | size | notes |
|---|---|---|---|---|---|
| p1 | uboot | 16384 | 8192 | 4M | signed (secure boot) |
| p2 | misc | 24576 | 8192 | 4M | bootloader control block |
| p3 | resource | 32768 | 32768 | 16M | kernel dtb + logo |
| p4 | kernel | 65536 | 24576 | 12M | signed |
| p5 | boot | 90112 | 40960 | 20M | ramdisk; signed |
| p6 | recovery | 131072 | 65536 | 32M | the locked recovery |
| p7 | backup | 196608 | 131072 | 64M | |
| p8 | cache | 327680 | 262144 | 128M | |
| p9 | metadata | 589824 | 32768 | 16M | |
| p10 | kpanic | 622592 | 8192 | 4M | |
| p11 | system | 630784 | 1228800 | 600M | Android /system |
| p12 | radical_update | 1859584 | 131072 | 64M | tigermedia OTA staging |
| p13 | userdata | 1990656 | 13279232 | 6484M | /data (to end of disk) |

On the plain TOUCH, system was p12; here it is **p11**. Resolve by name
(`/dev/block/platform/1021c000.rksdmmc/by-name/`), never by hardcoded pN.

## Full-disk backup (2026-11-23)

`dump/mmcblk0.img` (gitignored) - 7,818,182,656 bytes, raw `dd` of
`/dev/block/mmcblk0` over `adb exec-out`, 2448 s (~3 MiB/s).
Whole-file SHA-256 in `dump/mmcblk0.img.sha256`.

Verified by hashing every region on the live device and the matching
slice of the image:

| region | result |
|---|---|
| first 4 MB (loader), uboot, misc, resource, kernel, boot, recovery, backup, cache, metadata, kpanic, system, radical_update | **identical** |
| userdata | differs - expected: /data is mounted and was written to between the dump and the check. The image holds a crash-consistent snapshot of it. |
