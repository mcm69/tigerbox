# Audio box: internet radio (spec draft)

**Status: decided, 2026-11-25; ready to build.** Adds a curated internet radio tab to the audio box app
described in [APP-SPEC.md](APP-SPEC.md) ("section N" below refers to that file unless it says
"R"). Decisions are marked **(decided, 2026-11-25)**; section R12 lists them.

## R1. What it is

A second tab on the home screen with a small, parent-curated set of live stations. A child
taps a station to hear it, like a playlist. There is no browsing or searching: stations are
added from the Mac only.

**Non-goals:** station search or directory, recording, pause-and-continue (timeshift),
podcasts, alarms, playing over Bluetooth.

## R2. What the prototype showed (2026-11-25)

Tested with Klassikaraadio (`https://live.err.ee/live/klassikaraadio.m3u8`: HLS, AAC in
`.ts` segments, TLS 1.2+ only, Sectigo chain under USERTrust RSA, which the box trusts).

| | Result |
|---|---|
| Platform `MediaPlayer` | **Fails.** HLS goes to Rockchip's `RockFFPlayer`, which can't negotiate TLS with the server ("Unable to negotiate TLS/SSL session"). Plain http only redirects to https. |
| ExoPlayer (Media3 1.4.1, HLS module) | **Works.** Sound after ~6 s, clean, 2 min with the screen off, no errors, no LMK kills. It uses Java networking (Conscrypt, TLS 1.2). |
| Cost | APK +~4 MB. Test app ~33 MB PSS while streaming vs ~17 MB for audiobox idle, so expect ~+15 MB while radio plays. Free memory at the time: ~54 MB free + ~190 MB cache. |

Consequences:

- Radio playback uses **ExoPlayer** for every station (one code path; it also handles plain
  MP3/AAC/Icecast streams). Local playlists stay on `MediaPlayer`, which is proven on the box.
- This relaxes the "no AndroidX" rule of section 2 for Media3 only. ExoPlayer is created when
  a station starts and released when radio stops, so its memory is only paid while
  listening.
- Whether a station works depends on its protocol, TLS and certificates, so every station is
  test-played on the box before it is added (R9).

## R3. Network

- The box joins Wi-Fi once, through Android settings (admin screen, section 7). The
  on-screen keyboard is disabled by the lockdown (section 11), so `tools/audiobox keyboard
  on|off` switches it on for that and off again.
- The app watches connectivity (`ConnectivityManager` broadcast). Offline, the radio tab
  stays visible but its tiles are dimmed with a small "no Wi-Fi" mark; tapping one gives the
  red error flash (section 9) and a short "No internet" message.
- While a station plays: a `WifiLock` (full mode) plus ExoPlayer's network wake mode, so the
  stream keeps going with the screen off.
- Wi-Fi stays on all the time **(decided, 2026-11-25; revisit after measuring)**. Turning it
  off when radio isn't playing saves battery, but reconnecting adds seconds to every start.
  Battery drain is measured in milestone R5.
- Android's automatic network time is on **(decided, 2026-11-25)**, so the clock stays right
  whenever Wi-Fi is up (`settings global auto_time` = 1, switched on 2026-11-25). The time
  zone stays Europe/Tallinn: automatic time zone only works from a mobile network, which the
  box doesn't have. `tools/audiobox time` stays for offline use.

## R4. Stations

A station is an entry in `radio.json` plus an optional logo:

```
/sdcard/audiobox/
  radio/<id>.png|jpg           optional station logo (else a letter tile)
  radio.json                   the stations (written by the Mac tool and admin screen)
```

```json
{ "version": 1,
  "stations": {
    "klassika": { "label": "Klassikaraadio", "url": "https://live.err.ee/live/klassikaraadio.m3u8",
                  "order": 1, "bedtime": true },
    "vikerraadio": { "label": "Vikerraadio", "url": "https://…", "order": 2, "hidden": true } } }
```

- `url` is the resolved stream URL (the tool resolves `.pls`/`.m3u` wrappers and redirects
  when adding).
- `label`, `order`, `hidden` and `bedtime` work as for playlists (section 5.2). There is no
  `resume` or `shuffle`: radio is always live.
- Same concurrency rules as `library.json` (section 6): pull, edit, push, `RELOAD`.

## R5. Screens

- **Tabs (decided, 2026-11-25):** two big pill tabs centred in the top bar, between the clock and the
  battery: a music note and a radio icon, no text. Only shown when radio is switched on and
  at least one station exists; otherwise the home screen looks exactly as now. The tab
  showing on screen-on is the one of whatever is loaded, else the last one used.
- **Radio grid:** same 4 × 2 tile grid and paging as playlists. The playing station gets the
  same highlight ring and badge.
- **Radio player:** the playlist player layout (section 7) with these changes:
  - logo left, station name as the title;
  - the song line shows "now playing" metadata when the stream sends it (Icecast
    `StreamTitle`, HLS ID3), else nothing;
  - instead of track dots and the progress bar: a "LIVE" pill, or "Connecting…" while
    buffering;
  - ⏮ / ⏭ switch to the previous / next visible station;
  - ⏯ pauses by stopping the stream; play reconnects to the live stream (no timeshift).
- **Errors:** if a station can't be reached, the player shows "Can't reach this station"
  with the logo greyed out, and the red error flash.

## R6. Playback

- `PlayerService` gets a second source next to playlists: `play(playlistId)` and
  `playStation(stationId)` share the `MediaSession`, audio focus, the big button, the sleep
  timer and the lights. Starting one stops the other.
- Station start: build an ExoPlayer, set the URL, prepare, play. While buffering the state
  is "connecting".
- **Reconnects (decided, 2026-11-25):** on a stream error or stall, retry after 2, 5, 10 and 30 s,
  then every 30 s, giving up after 2 minutes. Each retry starts again at the live edge.
  Connectivity coming back triggers an immediate retry.
- **Bitrate (decided, 2026-11-25):** cap HLS at ~160 kbit/s (Klassikaraadio's 130k variant). That's
  ~60 MB an hour, and it starts faster on slow Wi-Fi.
- Big button with nothing loaded resumes the last thing played, playlist or station
  (`state.json` `last` becomes `{"type": "station", "id": …}` for stations).

## R7. Parental controls

| Control | Radio |
|---|---|
| Admin switch "Radio" | Default off **(decided, 2026-11-25)**; turning it on shows the tab once stations exist |
| Volume caps | Same as playlists |
| Sleep timer | Same: fade and stop |
| Idle auto-off | A playing (or connecting) station counts as activity |
| Bedtime window | Only stations marked `bedtime` play, others dimmed, as for playlists **(decided, 2026-11-25)** |
| Admin screen | A *Radio* section: the switch, plus reorder, rename and hide per station. Adding and removing is done from the Mac |

Accepted risk: live radio plays whatever the station broadcasts (ads, news, talk). Curating
the stations is the only filter.

## R8. Lights

As section 9, with one addition: while connecting, the ring is steady (green, or yellow at low
battery). It starts breathing once audio actually plays. The bottom light also follows the
playing state, not the connecting one.

## R9. Mac tool

```
audiobox radio add <url> [id] [--label ..] [--icon file] [--bedtime] [--hidden]
audiobox radio list                    # stations, flags, last check result
audiobox radio set <id> key=value ...  # label, url, order, bedtime, hidden
audiobox radio rm <id>
audiobox radio check [id]              # test-play one or all stations on the box
audiobox keyboard on|off               # on-screen keyboard, for joining Wi-Fi
```

- `radio add` resolves `.pls`/`.m3u` wrappers and redirects on the Mac and reads the content
  type. It then runs the same test as `radio check` before saving.
- **Check on the box:** the tool sends `dev.audiobox.RADIO_CHECK` with the URL. The app plays
  it muted for up to 15 s and writes the result to `radio-check.json`: ok/error, time to
  first audio, codec and bitrate. The tool waits for that file. A playing station is never
  interrupted; the check is refused while something plays.

## R10. Memory

ExoPlayer only exists while a station is starting or playing. Target as in section 11: no
LMK kills in a day of mixed use. Measure audiobox PSS while streaming; if it's well above
~35 MB, look at the ExoPlayer buffer sizes (the default `LoadControl` buffers up to 50 s,
and live radio needs much less).

## R11. Milestones

| | Scope | Done when |
|---|---|---|
| R1 | Media3 in the app, `playStation` in `PlayerService`, `radio.json` read, a hidden test broadcast to play a URL | Klassikaraadio plays from audiobox itself; PSS measured |
| R2 | Radio tab, station grid and player screen, metadata line, error and offline states | A child can switch tabs, pick a station, skip stations, pause and play |
| R3 | Reconnects, Wi-Fi lock, connectivity handling, lights, sleep timer, bedtime, idle off, resume last | Survives Wi-Fi off/on and a router reboot mid-stream; screen off for 30 min |
| R4 | Mac tool `radio` commands and on-box check; `keyboard on/off`; admin Radio section | A station added from the Mac in one command |
| R5 | A day of mixed use; battery drain with Wi-Fi on vs off measured | No LMK kills; Wi-Fi policy decided (R3) |

## R12. Decisions (2026-11-25)

All open points are settled:

- Tabs in the top bar (R5).
- Radio off until switched on in the admin screen (R7).
- Wi-Fi always on, to be revisited after measuring battery drain (R3, milestone R5).
- Automatic network time on (R3).
- A per-station bedtime flag, like playlists (R7).
- The reconnect schedule and the ~160 kbit/s cap as written (R6).
- Stations: Klassikaraadio to start with. More are added later with `audiobox radio add`;
  the station list itself is outside this spec.
