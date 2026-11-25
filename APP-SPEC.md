# Audio box app: spec (draft)

**Status: draft, 2026-11-24.** This spec covers the app that replaces the stock tigerbox app.
Its hardware facts come from [SURVEY.md](SURVEY.md); section numbers written § refer to that
file, and sections of this spec are written "section N". Product decisions made so far are
marked **(decided)**. The open points were settled on 2026-11-24 (section 14).

## 1. What it is

An offline audio box. No account, no cloud.

- **Phase 1 (decided):** the home screen shows one big icon per playlist. A playlist is a
  folder of audio pre-loaded from a Mac. A child taps an icon to play it and uses the big
  button for play/pause.
- **Phase 2:** NFC cards. A card is just a shortcut to a playlist, so putting a card in does
  the same as tapping that icon. This waits until there are real NTAG cards or stickers to
  test with (section 13). Phase 1 is built so cards slot in without restructuring.

A parent manages the box from a Mac over ADB and through a locked admin screen on the box.

**Non-goals for v1:** streaming, Wi-Fi (a curated internet radio tab is specced separately
in [RADIO-SPEC.md](RADIO-SPEC.md)), Bluetooth headphones (§7: these would mean
reimplementing the serial protocol), recording, and app updates over the air.

## 2. Platform constraints

| Constraint | Consequence |
|---|---|
| Android 5.1.1 (API 22), 512 MB RAM, low-memory killer from ~83 MB free (§1) | `minSdk`/`targetSdk` 22. Plain Views: no Compose, no WebView, no AndroidX libraries that need newer APIs. One process. Decode icons at display size. |
| 800 × 480 landscape, mdpi (§4) | 1 dp = 1 px. Design at 800 × 480 directly. Touch targets ≥ 96 px. |
| No platform key (§6) | Normal signing. The app is installed to `/system/priv-app` so it gets `SHUTDOWN` and `SET_TIME` (signature\|system on 5.1). |
| The LED sysfs files (and `/dev/mf175x` for phase 2) are world-writable | Usable directly, without root. |
| `/dev/i2c-2` (SN3199 bottom light) is `system:system 0660` | Not accessible to the app as it stands. See milestone M5 (section 12). |
| System language German | UI text is English (decided); a bundled font covers Latin incl. umlauts. |

## 3. Components

One APK, one process:

- **`HomeActivity`** is the kid UI: the playlist grid and the player screen (section 7). It
  registers as HOME (launcher), is single-task and is locked to landscape.
- **`AdminActivity`** holds the parent settings, behind the lock described in section 7.
- **`PlayerService`** is a foreground service that runs whenever a playlist is loaded. It owns
  the `MediaPlayer`, the `MediaSession` (required for the big button when the screen is off,
  see section 4), audio focus, the partial wake lock while playing, and saving resume
  positions. Its entry point is `play(playlistId)`. The grid uses it now, and cards will use it
  in phase 2.
- **`Library`** scans `library/`, merges what it finds with `library.json` and produces the
  list of playlists (section 5).
- **`PowerPolicy`** handles the button hold-to-off, the idle auto-off, the sleep timer and the
  bedtime window. It uses `AlarmManager` plus the service.
- **`Lights`** handles the ring (`modem_status`, `breathled_switch`) and the tiger head
  (`laohutou_status`). It is write-only (§4). It also covers the SN3199 once milestone M5 is
  done.
- **`Store`** loads and saves the JSON files described in section 6, writing each one
  atomically (temporary file, then rename).
- **`RadioPlayer`** plays internet radio stations through ExoPlayer, for the radio tab
  ([RADIO-SPEC.md](RADIO-SPEC.md)). `PlayerService` holds either a playlist or a station.
- *Phase 2:* **`CardReader`** (section 13).

## 4. The big button (decided, tested 2026-11-24)

The keymap file `/system/usr/keylayout/rk816_pwrkey.kl` contains `key 116 MEDIA_PLAY_PAUSE`.
It is installed with the app and must not use the `WAKE` flag (§4).

| Gesture | Effect |
|---|---|
| Short press | Play/pause. If nothing is loaded, it resumes the last playlist. Arrives through the `MediaSession` when the screen is off and through `onKeyDown` in `HomeActivity` when it is on. Either way the app also wakes the screen briefly so the child sees a reaction. |
| Hold ~3 s | Power off. The ring turns red as a warning, the resume position is saved, then the app shuts the box down (`SHUTDOWN` permission). |
| Hold ~6–8 s | The PMIC cuts power in hardware. This is the fallback if the app hangs. |
| Power on | PMIC/charger, outside the app. When charging, a ~3 s hold is needed. |

Hold detection: the key arrives as down/up, so the app measures the time held. It does not
rely on key repeats.

## 5. Playlists

### 5.1 What a playlist is

- One folder under `library/`. Its id is the folder name (a lowercase slug, e.g.
  `gruffalo`).
- The tracks are the audio files directly in the folder, in natural filename order
  (`2 …` before `10 …`). Subfolders are ignored.
- Icon: `icon.png`/`icon.jpg` in the folder. If there's none, the first embedded cover art.
  If there's none of that either, a coloured tile with the label's first letter.
- Formats: whatever Android 5.1 decodes natively, which is MP3, AAC/M4A, Ogg Vorbis, FLAC and
  WAV. Opus is left out until tested. The Mac tool can transcode anything else.
- A folder with no playable files doesn't appear on the grid.

### 5.2 Settings per playlist (`library.json`)

| Field | Default | Meaning |
|---|---|---|
| `label` | folder name | Shown under the icon and on the player screen; keep it short (≈ 12 characters fit) |
| `order` | alphabetical | Position on the grid |
| `resume` | `true` | `false` always starts at track 1, which suits music |
| `shuffle` | `false` | Random order, reshuffled at each start from track 1 |
| `bedtime` | `false` | Allowed during the bedtime window (section 8) |
| `hidden` | `false` | Kept on the box but not on the grid. In phase 2 it can still be reached by card |

### 5.3 Resume (decided: remember per playlist)

- Each playlist stores its `{track, positionMs}` in `state.json`. It is saved on pause,
  playlist switch, track change, power-off, and every 15 s while playing.
- Within 30 s of the end of a track, resume moves to the next track.
- End of the playlist **(decided)**: stop and reset it to track 1.

### 5.4 Tapping an icon

| Situation | Effect |
|---|---|
| Different playlist from the one loaded | Save the current position, load the new playlist, resume and play, and show the player screen. |
| Same playlist as loaded | Show the player screen. If it was paused, play. No restart. |
| During the bedtime window, playlist not allowed | Show the bedtime illustration; nothing plays (section 8). |

## 6. Files on the box

Everything is under `/sdcard/audiobox/` (name decided; package `dev.audiobox`). The stock
app's `/sdcard/tigerbox` logs folder is left alone.

```
/sdcard/audiobox/
  library/<id>/                one folder per playlist
    01 First track.mp3         natural filename order
    icon.png|jpg               optional grid icon
  library.json                 per-playlist settings (section 5.2)
  settings.json                volume caps, timers, bedtime, PIN hash
  state.json                   resume positions, last playlist (app-owned)
  .nomedia                     keeps Android's media scanner out
```

`library.json`:

```json
{ "version": 1,
  "playlists": {
    "gruffalo":  { "label": "Der Grüffelo", "order": 1 },
    "lullabies": { "label": "Schlaflieder", "order": 2, "resume": false, "bedtime": true } } }
```

- Concurrency: the app owns `state.json`. `library.json` and `settings.json` are edited in two
  places. The Mac tool pulls, edits, pushes, then sends `am broadcast -a <pkg>.RELOAD`, and
  the app rescans and re-reads on that broadcast. The admin UI writes through `Store`.
- Never write `update.zip`/`update.img` anywhere under `/sdcard` (§2, §5).

## 7. Screens

**Kid UI (`HomeActivity`), 800 × 480:**

- *Look:* English UI text **(decided)** in a playful rounded font bundled with the app:
  [Nunito](https://fonts.google.com/specimen/Nunito) ExtraBold (SIL OFL; licence file
  shipped alongside). It covers Latin and Cyrillic, so playlist labels can be in either
  (Fredoka, the first pick, has no Cyrillic). Text is minimal; icons carry the meaning.
- *Grid (home):* a 4 × 2 grid of icons, each about 170 × 170 with its short label below
  **(decided: icons with labels)**. With more
  than 8 playlists it scrolls sideways a page at a time, with page dots. The playlist that's
  currently loaded has a highlight ring and a small "playing" badge. A 24-hour clock sits
  top left and a battery indicator top right. There is no other chrome.
- *Player:* the large icon (≈ 360 × 360) on the left. On the right: the label (one line), the song
  title (its title tag, else the filename without a leading track number; scrolls while
  playing when too long, ends in "…" when paused; tags are expected to be in a proper
  encoding when loaded), the track position (dots up to 12 tracks, "3 / 14" above that), big ⏮ ⏯ ⏭, a volume control (two
  big −/+ with a bar up to the cap), the sleep timer moon (section 8), and a large "back to
  grid" button. Swiping right also goes back.
- *Bedtime:* an illustration shown for a few seconds when a playlist isn't allowed.
- The screen turns off after the system timeout (60 s). Playback continues with the screen
  off. When the screen comes back on, it shows the player screen if something is loaded,
  otherwise the grid.

**Admin (`AdminActivity`):**

- Way in **(decided: admin lock)**: press and hold the top-left corner for 5 s, then enter a
  4-digit PIN. The PIN is stored as a salted hash in `settings.json` and can be reset from the
  Mac tool.
- Sections:
  - *Playlists*: reorder, rename, hide, and toggle resume, shuffle and bedtime. Adding files is
    done from the Mac.
  - *Volume*: speaker cap and headphone cap. Headphones are detected through
    `AudioManager.isWiredHeadsetOn()` / h2w (§7).
  - *Timers*: sleep timer default, idle auto-off minutes, and "auto-off while charging"
    (default off).
  - *Bedtime*: on/off, start and end time. Which playlists are allowed is set per playlist.
  - *Clock*: shows the current time, with a set button (`SET_TIME`). The Mac tool also syncs
    it.
  - *System*: battery, free space, app version, "exit to Android settings" (for maintenance). There is no navigation bar, so the way
    back is to let the screen turn off: the app then brings the home screen to the front
    (and an open admin screen closes, so the PIN is asked again).

## 8. Parental controls (decided: all four)

- **Volume cap:** separate caps for speaker and headphones, as a percentage of
  `STREAM_MUSIC`, which on this box runs 0–100 (not AOSP's 0–15; seen in `dumpsys audio`
  2026-11-24). The app clamps the volume whenever the volume, output or settings change. The
  defaults are **100 % for both (decided)**, i.e. no cap until a parent sets one.
- **Sleep timer:** default off. The moon button cycles off → 15 → 30 → 45 min. When it runs out, the volume
  fades over 10 s and playback pauses. The admin sets the default, which is applied
  automatically during bedtime.
- **Idle auto-off:** when nothing has played and nothing has been touched for N minutes
  (**default 15, decided**), the box shuts down. While charging it never does this unless
  the admin setting "auto-off while charging" is turned on (**decided: default off**).
- **Bedtime window:** **off by default (decided)**; when turned on, the times are prefilled
  as 19:00–07:00. Between start and end only playlists marked `"bedtime": true` play, and
  the grid dims the others. The sleep timer is forced on. This depends on a correct clock: the
  RTC holds its time once set, and the Mac tool sets it on every sync.
- **Admin lock:** described in section 7.

## 9. Lights (decided)

Changed 2026-11-25: the ring is steady while playing and breathes when paused or idle
(breathing during playback was distracting).

| State | Ring (`modem_status` / `breathled_switch`) | Tiger head (`laohutou_status`) |
|---|---|---|
| Idle, ready | green breathing (`1`) | dim |
| Playing | green steady | on |
| Paused | green breathing (`1`) | dim |
| Error / not allowed | red flicker (`000011`) 3 s | unchanged |
| Low battery (< 15 %, not charging) | yellow instead of green: steady while playing, else breathing (`7`) | unchanged |
| Powering off (button held) | red steady | off |

The bottom blue light (SN3199) breathes slowly while playing and is off otherwise. A
switch in the admin screen (Lights) turns it off for good; it is on by default. At boot
the kernel driver puts the chip into a sound-reactive mode (`led_debug` = 1); the app
switches that off and drives the chip through `/system/bin/sn3199w`.
`tools/audiobox install` pushes that helper and appends `chmod 0666 /dev/i2c-2` to
`/system/bin/install-recovery.sh`, which init runs as root at every boot (SELinux is
permissive). Without that access the light just stays off.

## 10. Mac tool: `tools/audiobox`

A single script (POSIX sh or Python, decided at build time) that runs over `adb`:

```
audiobox status                        # battery, free space, clock, playlists
audiobox push <local-dir> [id]         # copy a folder into library/ (skips existing files)
         [--label ..] [--icon file] [--no-resume] [--bedtime] [--transcode]
audiobox list                          # playlists with settings and size
audiobox set <id> key=value ...        # edit library.json
audiobox rm <id>                       # delete a playlist (asks first)
audiobox time                          # set the box clock from the Mac (root via adb root)
audiobox pin-reset
audiobox install <apk>                 # copy to /system/priv-app (already rw) + reboot
audiobox lockdown [--undo]             # stock app and unused packages off (section 11)
audiobox radio add|list|set|rm|check   # internet radio stations (RADIO-SPEC R9)
audiobox keyboard on|off               # on-screen keyboard, for joining Wi-Fi
```

Every command that changes something ends with the `RELOAD` broadcast. Pushing runs at about
3 MB/s (§2), so roughly 5–6 minutes per GB.

## 11. Memory and stock apps

Per §1/§8: after the app is HOME and working, `pm disable` the updater, NFC, managed
provisioning, mediafloat and LatinIME, and every component of the stock app except its
device-admin receiver (disabling the whole package boot-loops the box; NOTES.md M5). Done by
`tools/audiobox lockdown`. Target: no `lowmemorykiller` kills in a full day
of use.

## 12. Milestones

**Progress (2026-11-24):** M0–M5 done and verified on the box (see NOTES.md). Open from M5: a full day without LMK kills, and the optional `/dev/i2c-2` script.

| | Scope | Done when |
|---|---|---|
| M0 | Gradle project, user-app install, `Library` scan + `library.json` | The app lists the pushed folders with their icons |
| M1 | `PlayerService` + `MediaSession` + resume | Playlists play, switch and resume across a reboot |
| M2 | Kid UI (grid + player), big button (keymap installed), lights | Screen-off button press verified; hold-3 s power-off works |
| M3 | Admin screen + parental controls + clock | All four controls work; PIN lock |
| M4 | `tools/audiobox` | Push with icon and label, set, time sync |
| M5 | HOME + `/system/priv-app` + disable the stock apps. Done since: `/system/bin/install-recovery.sh` does `chmod 0666 /dev/i2c-2` at boot for the bottom light (it runs as root through the existing `flash_recovery` service; see section 9) | Clean boot straight into the app; a day with no LMK kills |

## 13. Phase 2: cards (later)

This waits for NTAG cards or stickers. The transit card (4-byte UID) is enough to build
against, but the 7-byte frame must be confirmed before shipping.

- **`CardReader`:** a receiver registered in the manifest for `com.android.bts84.nfcin`. On
  `nfcin` it reads 106 bytes from `/dev/mf175x`. The read raises `KEY_WAKEUP`, so a card wakes
  the box.
- **UID parse (§3):** if byte 0 of the last 10 bytes is `88`, it is a 7-byte UID at bytes
  1–3 and 5–8. Otherwise it is a 4-byte UID at bytes 0–3. Verify the BCC(s) and reject an
  all-zero frame. On a bad read, retry once after 200 ms. Logs keep only the last 2 bytes of a
  UID. NDEF and URL data on the cards are ignored.
- **Mapping:** `cards.json`, UID → playlist id. A known card calls `play(playlistId)` and
  follows the same rules as a tap (section 5.4), including resume and bedtime.
- **Card out (decided): playback continues.** The `nfcout` broadcast is ignored.
- **Unknown card:** playback is not interrupted. The ring flickers red, and the UID is saved
  as `lastUnknownUid` in `state.json`.
- **Binding:** in the admin, "Learn card", then put the card in and pick a playlist. From the
  Mac: `audiobox bind <id> --last-unknown`.
- `hidden` playlists (section 5.2) become card-only content.

## 14. Decisions on the former open points (2026-11-24)

1. **Name:** `audiobox`: data folder `/sdcard/audiobox/`, Mac tool `tools/audiobox`, package
   `dev.audiobox`.
2. **Labels and language:** icons with a short label; English; playful font (Fredoka, replaced by Nunito on 2026-11-24 for Cyrillic labels).
3. **End of a playlist:** stop and rewind to track 1.
4. **Idle auto-off while charging:** never by default; admin setting to turn it on.
5. **Defaults:** volume caps 100 % (speaker and headphones), idle auto-off 15 min, sleep timer
   off, bedtime off.
6. **Lights:** the mapping in section 9 as proposed.

Nothing is open for phase 1. Phase 2 still needs a real NTAG card or sticker (section 13).
