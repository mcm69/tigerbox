# audiobox

Offline playlist player for the repurposed tigerbox TOUCH PLUS. Spec:
[../APP-SPEC.md](../APP-SPEC.md). Plain Java and Views, API 22, no AndroidX.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew testDebugUnitTest assembleDebug
../tools/audiobox install app/build/outputs/apk/debug/app-debug.apk   # system app + reboot
../tools/audiobox lockdown                                            # once, after the first install
```

The app runs from `/system/priv-app` (it needs `SHUTDOWN` and `SET_TIME`)
and is the box's home screen. A plain `adb install -r` would put an
update in `/data/app` on top of the system copy; use the tool instead so
`/system` holds the current build (it reboots). Note: never `pm disable media.tiger.tigerbox` as a whole
package, that boot-loops the box (see NOTES.md, M5).

Content goes to `/sdcard/audiobox/library/<id>/` (audio files plus an
optional `icon.png`), with settings in `/sdcard/audiobox/library.json`.
After changing files, send `adb shell am broadcast -a dev.audiobox.RELOAD`.
