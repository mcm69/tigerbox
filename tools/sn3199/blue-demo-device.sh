# Runs ON THE BOX (adb push + sh). Demo of the SN3199 bottom light:
#   1. each output alone, with dark gaps, to identify the blue outputs
#   2. a smooth software fade (all outputs; only the blue ones have LEDs)
#   3. hardware auto-breathing (the chip fades by itself)
# All at 5 mA max current. Ends with a chip reset (dark).
W=/data/local/tmp/sn3199w
t0=$(date +%s)
w() { $W "$@" 2>&1 | busybox grep -v '^WARNING: linker'; }
say() { echo "[+$(( $(date +%s) - t0 ))s] $*"; }
nap() { busybox usleep "$1" 2>/dev/null || sleep 1; }

OFF="0x07=0 0x08=0 0x09=0 0x0a=0 0x0b=0 0x0c=0 0x0d=0 0x0e=0 0x0f=0"
all() { echo "0x07=$1 0x08=$1 0x09=$1 0x0a=$1 0x0b=$1 0x0c=$1 0x0d=$1 0x0e=$1 0x0f=$1"; }

w 0xff=0 0x00=0x01 0x04=0x30 0x03=0x00 0x01=0x77 0x02=0x07 $OFF 0x10=0
say "setup done, dark for 3 s"
sleep 3

# --- 1. each output alone: 3 s on, 2 s dark.
# The button ring is the slot counter: green while an output is on, dark in
# the gaps, so the Nth green flash = OUTn even when OUTn has no LED.
RING=/sys/devices/pwmleds.20/modem_status
echo 0 > /sys/devices/pwmleds.20/breathled_switch
echo 000000 > $RING
for k in 1 2 3 4 5 6 7 8 9; do
    reg=$(busybox printf '0x%02x' $((0x06 + k)))
    say "PART 1: OUT$k ON (ring flash #$k)"
    w $OFF "$reg=0xff" 0x10=0
    echo 001000 > $RING
    sleep 3
    w $OFF 0x10=0
    echo 000000 > $RING
    sleep 2
done

# --- 2. smooth fade up and down, gamma-ish (value = i*i/4), twice
say "PART 2: software fade x2"
for round in 1 2; do
    i=0
    while [ $i -le 32 ]; do
        v=$(( i * i / 4 )); [ $v -gt 255 ] && v=255
        w $(all $v) 0x10=0; nap 40000; i=$((i + 1))
    done
    while [ $i -ge 0 ]; do
        v=$(( i * i / 4 )); [ $v -gt 255 ] && v=255
        w $(all $v) 0x10=0; nap 40000; i=$((i - 1))
    done
done
sleep 2

# --- 3. hardware breathing: groups RGB1-3 in auto-breathing mode
#   T1-T3 regs 0x1a-0x1c = 0x13: rise/fall A=3 -> 260ms*2^3 = 2.08 s, hold B=1 -> 0.26 s
#   T4 regs 0x1d-0x25 = 0x02: off time A=2,B=0 -> 0.52 s
#   T0 regs 0x11-0x19 = 0: no start delay. PWM = peak brightness.
say "PART 3: hardware breathing (no CPU involved) for 15 s"
# Order matters: switch to breathing mode BEFORE loading the peak PWM with
# 0x10. Loading 0xff while still in plain PWM mode lights the LEDs at full
# brightness for a moment (seen on the first run, 2026-11-23).
w 0x03=0x70 \
  0x11=0 0x12=0 0x13=0 0x14=0 0x15=0 0x16=0 0x17=0 0x18=0 0x19=0 \
  0x1a=0x13 0x1b=0x13 0x1c=0x13 \
  0x1d=0x02 0x1e=0x02 0x1f=0x02 0x20=0x02 0x21=0x02 0x22=0x02 0x23=0x02 0x24=0x02 0x25=0x02 \
  $(all 0xff) 0x10=0 0x26=0
sleep 15

say "chip reset -> dark"
w 0xff=0
echo 001000 > $RING
say "done (ring back to green)"
