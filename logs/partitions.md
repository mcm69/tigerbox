# eMMC partition table (RK3128 tigerbox TOUCH PLUS)

Derived from the kernel command line (`mtdparts=rk29xxnand:...`,
`storagemedia=emmc`) in logs/2026-11-23-uart-boot-full.log. Units are
512-byte sectors. eMMC user area = 7456 MB (UserCapSize). Partitions map
to /dev/block/mmcblk0pN in listed order; prefer the by-name symlinks under
/dev/block/platform/*/by-name/ since the numbering differs from the TOUCH.

| # | name | start (sct) | size (sct) | size | notes |
|---|---|---|---|---|---|
| p1 | uboot | 8192 | 8192 | 4M | signed (secure boot) |
| p2 | misc | 16384 | 8192 | 4M | bootloader control block |
| p3 | resource | 24576 | 32768 | 16M | kernel dtb + logo |
| p4 | kernel | 57344 | 24576 | 12M | signed |
| p5 | boot | 81920 | 40960 | 20M | ramdisk; signed |
| p6 | recovery | 122880 | 65536 | 32M | the locked recovery |
| p7 | backup | 188416 | 131072 | 64M | |
| p8 | cache | 319488 | 262144 | 128M | |
| p9 | metadata | 581632 | 32768 | 16M | |
| p10 | kpanic | 614400 | 8192 | 4M | |
| p11 | system | 622592 | 1228800 | 600M | Android /system - edit target |
| p12 | radical_update | 1851392 | 131072 | 64M | tigermedia OTA staging |
| p13 | userdata | 1982464 | 13287424 | 6488M | /data (rest of eMMC) |

NB: on the plain TOUCH, system was p12; here it is **p11**. Do not reuse
hardcoded partition numbers from the wiki - resolve by name.
