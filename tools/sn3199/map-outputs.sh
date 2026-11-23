#!/bin/sh
# Light SN3199 outputs OUT1..OUT9 one at a time so they can be watched or
# photographed. Runs from the Mac; needs sn3199w pushed to /data/local/tmp.
#
# Safe settings: 5 mA max current (reg 0x04 = 0x30), PWM 0x80 (half of that),
# plain PWM mode with audio sync off. Ends with a chip reset (0xFF), which
# returns every register to power-on defaults: software shutdown, dark.
set -eu
W=/data/local/tmp/sn3199w
HOLD=${HOLD:-5}
LEVEL=${LEVEL:-0x80}

# Android 5.1's linker warns about newer ELF entries the NDK emits; harmless.
w() { adb shell "$W $* 2>&1; echo rc=\$?" | tr -d '\r' | grep -v '^WARNING: linker' | grep -v '^rc=0$' || true; }

ALL_OFF="0x07=0 0x08=0 0x09=0 0x0a=0 0x0b=0 0x0c=0 0x0d=0 0x0e=0 0x0f=0"

echo "[setup] reset, normal mode, 5 mA, PWM mode, all outputs enabled, all dark"
w 0xff=0 0x00=0x01 0x04=0x30 0x03=0x00 0x01=0x77 0x02=0x07 $ALL_OFF 0x10=0
echo "[setup] dark for 5 s - get ready"
sleep 5

t0=$(date +%s)
for k in 1 2 3 4 5 6 7 8 9; do
    reg=$(printf '0x%02x' $((0x06 + k)))
    group=$(( (k - 1) / 3 + 1 ))
    echo "[+$(( $(date +%s) - t0 ))s] OUT$k (RGB$group, reg $reg=$LEVEL)"
    w $ALL_OFF "$reg=$LEVEL" 0x10=0
    sleep "$HOLD"
done

echo "[+$(( $(date +%s) - t0 ))s] all nine together"
w 0x07=$LEVEL 0x08=$LEVEL 0x09=$LEVEL 0x0a=$LEVEL 0x0b=$LEVEL 0x0c=$LEVEL 0x0d=$LEVEL 0x0e=$LEVEL 0x0f=$LEVEL 0x10=0
sleep "$HOLD"

echo "[+$(( $(date +%s) - t0 ))s] chip reset -> dark"
w 0xff=0
