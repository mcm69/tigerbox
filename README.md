# tigerbox TOUCH PLUS, repurposed

The tigerbox TOUCH PLUS is a kids' audio box built around a Rockchip RK3128 running
Android 5.1. Without the vendor's cloud service it is just a speaker with a screen. This
repo turns it into an offline music box that you control yourself: your own playlists, a few
internet radio stations, parental controls, and no account or cloud.

What the box does now:

- **Home screen:** a grid of playlist covers. Tap one to play it.
- **Big button:** a short press plays or pauses, or resumes whatever played last. Holding it
  powers the box off with a short goodbye.
- **Player screen:** cover, song title, track dots, skip and volume.
- **Radio tab:** a small set of stations picked by the parent, switched off by default.
- **Parental controls:** volume limits, a sleep timer, bedtime hours, auto-off when idle, and
  a PIN-locked admin screen.
- **Lights:** the button's ring and the light under the box show whether it's playing,
  paused or low on battery.
- **From the Mac:** `tools/audiobox` copies playlists in, adds stations and installs updates
  over adb.

## What's here

| Path | What |
|---|---|
| [SURVEY.md](SURVEY.md) | What the box is: hardware, partitions, the stock software, lights, button, card reader |
| [NOTES.md](NOTES.md) | The working log: getting root, the backup, decisions and dead ends, in order |
| [APP-SPEC.md](APP-SPEC.md) | Spec of the replacement app, with milestones |
| [RADIO-SPEC.md](RADIO-SPEC.md) | Spec of the internet radio tab |
| [audiobox/](audiobox/) | The Android app (Java, API 22); how to build and install it is in [audiobox/README.md](audiobox/README.md) |
| [tools/audiobox](tools/audiobox) | Command-line tool for the Mac: `push`, `list`, `set`, `radio …`, `install`, `lockdown` |
| [tools/sn3199/](tools/sn3199/) | Small helper that drives the LED chip for the light under the box |
| [tools/chimes.py](tools/chimes.py) | Generates the startup and shutdown chimes |
| [survey/](survey/), [logs/](logs/) | Raw command output, boot logs and photos behind the survey (device identifiers redacted) |

## Before you try this

- **It needs root,** reached here through the board's serial console (see NOTES.md). The
  app installs itself into `/system` and replaces the stock app. Make a full eMMC backup
  first: that is the only way back.
- **It is specific to one device.** It was built and tested on one TOUCH PLUS. Other
  revisions may differ.
- **It's not affiliated with the vendor.** This project has nothing to do with Tiger Media,
  and "tigerbox" is their trademark. The repo contains no vendor firmware, APKs or decompiled
  code.
- **You use it at your own risk.** It voids whatever warranty is left, and you could brick
  your box.

## Licence

MIT, see [LICENSE](LICENSE). The bundled Nunito font (SIL OFL 1.1) and the Gradle wrapper
(Apache 2.0) keep their own licences; [audiobox/README.md](audiobox/README.md#licence) lists them.
