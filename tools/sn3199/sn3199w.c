/*
 * sn3199w - write registers of the SN3199 LED driver on the tigerbox TOUCH PLUS.
 *
 * The SN3199 is write-only (see SURVEY.md, "SN3199 register map"), so this
 * tool only writes. The kernel's sn3199 driver already owns the address, so
 * we use I2C_SLAVE_FORCE; that driver only touches the chip when someone
 * writes /sys/bus/i2c/devices/i2c-2/2-0064/led_debug, so don't do both at once.
 *
 * Usage: sn3199w [-b BUS] [-a ADDR] [--allow-high-current] REG=VAL [REG=VAL ...]
 *   REG and VAL accept decimal or 0x-prefixed hex.
 *   Writes are sent in order, one I2C transaction each.
 * Example (light OUT1 at 25% with 5 mA max current):
 *   sn3199w 0x00=0x01 0x04=0x30 0x03=0x00 0x01=0x77 0x02=0x07 0x07=0x40 0x10=0
 */
#include <errno.h>
#include <fcntl.h>
#include <linux/i2c-dev.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#ifndef I2C_SLAVE_FORCE
#define I2C_SLAVE_FORCE 0x0706
#endif

#define REG_CONFIG2 0x04

static int parse_byte(const char *s, unsigned *out) {
    char *end;
    errno = 0;
    unsigned long v = strtoul(s, &end, 0);
    if (errno || end == s || *end || v > 0xFF) return -1;
    *out = (unsigned)v;
    return 0;
}

/* Config register 2, bits 6-4 select max output current. Values per datasheet. */
static int current_ma(unsigned cfg2) {
    static const int ma[8] = {20, 15, 10, 5, 40, 35, 30, 25};
    return ma[(cfg2 >> 4) & 7];
}

static void usage(const char *prog) {
    fprintf(stderr, "usage: %s [-b BUS] [-a ADDR] [--allow-high-current] REG=VAL ...\n", prog);
}

int main(int argc, char **argv) {
    int bus = 2;
    unsigned addr = 0x64;
    int allow_high = 0;
    int first = 1;

    for (; first < argc && argv[first][0] == '-'; first++) {
        if (!strcmp(argv[first], "-b") && first + 1 < argc) {
            bus = atoi(argv[++first]);
        } else if (!strcmp(argv[first], "-a") && first + 1 < argc) {
            if (parse_byte(argv[++first], &addr) || addr > 0x7F) {
                fprintf(stderr, "bad address\n");
                return 2;
            }
        } else if (!strcmp(argv[first], "--allow-high-current")) {
            allow_high = 1;
        } else {
            usage(argv[0]);
            return 2;
        }
    }
    if (first >= argc) {
        usage(argv[0]);
        return 2;
    }

    /* Validate everything before touching the bus. */
    int n = argc - first;
    unsigned regs[256], vals[256];
    if (n > 256) {
        fprintf(stderr, "too many writes\n");
        return 2;
    }
    for (int i = 0; i < n; i++) {
        char buf[32], *eq;
        strncpy(buf, argv[first + i], sizeof buf - 1);
        buf[sizeof buf - 1] = 0;
        eq = strchr(buf, '=');
        if (!eq) {
            fprintf(stderr, "expected REG=VAL, got '%s'\n", argv[first + i]);
            return 2;
        }
        *eq = 0;
        if (parse_byte(buf, &regs[i]) || parse_byte(eq + 1, &vals[i])) {
            fprintf(stderr, "bad byte in '%s'\n", argv[first + i]);
            return 2;
        }
        if (regs[i] == REG_CONFIG2 && current_ma(vals[i]) > 20 && !allow_high) {
            fprintf(stderr, "refusing %d mA (reg 0x04=0x%02x); pass --allow-high-current\n",
                    current_ma(vals[i]), vals[i]);
            return 2;
        }
    }

    char dev[32];
    snprintf(dev, sizeof dev, "/dev/i2c-%d", bus);
    int fd = open(dev, O_RDWR);
    if (fd < 0) {
        fprintf(stderr, "open %s: %s\n", dev, strerror(errno));
        return 1;
    }
    if (ioctl(fd, I2C_SLAVE_FORCE, addr) < 0) {
        fprintf(stderr, "I2C_SLAVE_FORCE 0x%02x: %s\n", addr, strerror(errno));
        close(fd);
        return 1;
    }

    int rc = 0;
    for (int i = 0; i < n; i++) {
        unsigned char msg[2] = {(unsigned char)regs[i], (unsigned char)vals[i]};
        if (write(fd, msg, 2) != 2) {
            fprintf(stderr, "write 0x%02x=0x%02x: %s\n", regs[i], vals[i], strerror(errno));
            rc = 1;
            break;
        }
    }
    close(fd);
    return rc;
}
