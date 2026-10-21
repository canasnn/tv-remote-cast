# Changelog

All notable changes to **TV Remote & Cast** are documented in this file.

## [1.0.1] - 2026-10-21

### TV receiver
- Remote keys work like YouTube on a TV: Left/Right seek 5 seconds (hold to keep seeking), OK plays and pauses, Up/Down go to the on-screen buttons, Back closes them first.
- Presses in a row add up (+15s and the target time on screen) and the player seeks once, instead of reloading for every press.
- Much smoother seeking: 50 seconds ahead are kept loaded, the last 30 seconds stay in memory, playback restarts after 1 second of data, and connections are reused.
- Fixed a crash of the release build on some TVs.

### Phone app
- Keyboard: no lost characters when typing fast, deleting or mixing digits and letters.
- The TV volume no longer shows 0% before the TV has reported it.

## [1.0.0] - 2026-10-21

First release.

### Remote
- Android TV remote over the TV's own remote protocol: D-pad, touchpad, number pad, voice search, power, volume and channel keys.
- Keyboard that types straight into the TV's focused text box (any language, Backspace, Enter).
- Finds TVs on the network by itself, wakes them from standby, pairs with the code on the TV.
- App shortcuts, custom shortcuts, layout customization, Material You and AMOLED themes.

### Cast
- Hidden, always-on TV receiver app (`receiver/`): playback starts on the TV by itself; the TV's apps are listed and opened without ADB.
- Browser with video detection, tabs, bookmarks, history, pop-up and ad blocking (worldwide lists, updated daily).
- IPTV lists (M3U, W3U, RSS) with groups, search, favourites and automatic updates.
- Photos, videos, music and files from the phone; playlists; downloads (HLS to MP4); phone player.
- Subtitles from files, links, web pages and OpenSubtitles.com (your own key), with style and timing settings.
- Now playing screen with the remote's volume control, seek, speed, audio and subtitle tracks, picture options.
- Backup and restore.

### Privacy
- No ads, no accounts, no analytics or crash reporting.
