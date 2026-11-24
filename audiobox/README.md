# audiobox

Offline playlist player for the repurposed tigerbox TOUCH PLUS. Spec:
[../APP-SPEC.md](../APP-SPEC.md). Plain Java and Views, API 22, no AndroidX.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.audiobox/.HomeActivity
```

Content goes to `/sdcard/audiobox/library/<id>/` (audio files plus an
optional `icon.png`), with settings in `/sdcard/audiobox/library.json`.
After changing files, send `adb shell am broadcast -a dev.audiobox.RELOAD`.
