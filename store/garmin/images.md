# PNTomeGo: Connect IQ Store images

## Re-check current requirements first

Sizes, counts and file formats for store images are set by Garmin and change over time.
The values below are what to prepare as a starting point, not a guarantee.
**User action:** before uploading, open the upload form in the Connect IQ developer portal
(https://developer.garmin.com/connect-iq/) and read the current image requirements. Adjust the images if they differ.

## What to prepare

| Image | Source | Notes |
|-------|--------|-------|
| App icon | `PNTomeGo/resources/` launcher icon (existing 65x65 PNG) | The artwork is unchanged by the rename. The portal may ask for a larger store icon; if so, export a larger version of the same artwork. |
| Hero / banner image | create by hand | Only if the portal asks for it. Use the app name, the PNTome logo and a watch screenshot. |
| Screenshots | Connect IQ simulator | At least a few, showing the main screens (see list below). Use one set per watch family if the portal requires it. |

Suggested screenshots
1. Main screen with the library list
2. Sync screen while syncing
3. Playback screen
4. Bookmarks or resume screen

Show only your own test content. No third-party copyrighted covers or titles.

## Capturing screenshots with the simulator

The simulator needs the Ubuntu 22.04 container described in the `connectiq-simulator` skill
(`.kiro/skills/connectiq-simulator/SKILL.md`). Use its helper script (`ciq-sim.sh`) to start the simulator and run the app.

1. Build a release or debug `.prg` for a representative device, for example `fenix847mm` (AMOLED) and `fenix6pro` (MIP):

   ```bash
   cd PNTomeGo
   ./build.sh fenix847mm
   ```

   See `./build.sh --help` and `PNTomeGo/INSTALL.md` for options.
2. Start the simulator and run the app on that device (`ciq-sim.sh start`, then `ciq-sim.sh run`, or `./run_in_simulator.sh`).
3. Bring the app to the screen you want. Use the scripts in `PNTomeGo/sim/` to feed simulated state if you need sync or playback screens without a real phone.
4. In the simulator menu use File > Save Screenshot (or take a window screenshot) and save it as PNG.
5. Crop to the watch display only, with no window frame, unless the portal asks for the frame.
6. Name files clearly, for example `fenix847mm-01-library.png`.
7. Keep the final images out of git unless you decide to publish them. Do not commit large binaries by accident.

## Checklist before upload

- [ ] Icon, screenshots and any hero image match the current portal requirements
- [ ] Screenshots show the PNTomeGo name, not the old "Gsound" name
- [ ] No personal data, real file names or private content visible
- [ ] File format and size limits met
