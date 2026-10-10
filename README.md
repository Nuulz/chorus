# Chorus

Screens in the world to watch videos together, in sync, on Minecraft 26.2 (Fabric).

Chorus only orchestrates. The browser is [pane](https://github.com/Nuulz/pane). Players, roles and
networking are the server you're on, so it works on any server and on [jukz](https://github.com/Nuulz/jukz)
worlds, where the host owns the world.

- The screen block keeps the playback state (URL, playing, position anchored to game time). Vanilla
  block entity sync sends it to everyone, late joiners too.
- Whoever placed the screen, the world owner and operators control it (`anyone_can_control` to open it up).
- Limits: screens per world (server), live screens, resolution and radius (client). Screens nobody looks
  at don't upload frames; screens out of range close their browser.
- Only https URLs from allowed sites, checked by the server and again by each client.

- Your own videos: "Your own video…" picks a file, converts it to WebM/AV1 when it's H.264 (on the GPU when there is one: NVIDIA, Intel, AMD or VAAPI; else SVT-AV1) (this
  Chromium has no proprietary codecs), and sends it through the Minecraft connection. The server keeps it in
  `<world>/chorus-media/`; each client downloads it to `chorus-cache/` and plays while downloading.
  Videos no loaded screen has shown for 5 minutes are deleted on both sides.
- ffmpeg is downloaded once on first launch into `chorus-ffmpeg/`: unmodified static builds (BtbN's LGPL
  builds for Linux/Windows, Martin Riedl's for macOS) mirrored at chromium.nuulm.com/ffmpeg/; sources are
  linked in each package's SOURCES.txt.

- Browse: use a screen's page by hand (click, scroll, type, back/forward), e.g. to log in to Telegram Web
  and open a video; "Share" sends its address to everyone. Settings (Mod Menu): accept any link, volume,
  live screens, range, quality.

- Share my screen (Linux for now): the desktop portal's picker → GStreamer (`gst-plugin-pipewire`) → live
  AV1/Opus WebM from ffmpeg → relayed by the server. Only the chosen app's sound goes in (a virtual
  "Chorus" output plus a loopback to your speakers); your own screen stays muted.

Config: `config/chorus-server.properties` and `config/chorus-client.properties`.
