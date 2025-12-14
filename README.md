# Clipper
![Time](https://hackatime-badge.hackclub.com/U0922GMGGTU/Clipper?label=Time+spent+on+this)
![Downloads](https://img.shields.io/modrinth/dt/clipper/latest.svg?color=blue)
---
This mod adds a keybind to the game, that when pressed, sends a message to OBS to save replay buffer.
Then, it uses [blur](https://github.com/f0e/blur) to blur the video.
When that's done, it uploads the video to a [Zipline](https://zipline.diced.sh) server.
After the upload finishes, it gives you the URL to the clip you clipped.
---
**Settings**:
- **OBS WebSocket URL**: Open up OBS, in top-left toolbar, it's inside Tools > WebSocket Server Settings. It only shows you port, default is 4455. You can leave it that way and the default config for this will work.
- **OBS WebSocket Password**: It's on the same tab, down to Server Port. Copy it and paste it here.
- **Zipline URL**: The Zipline server's URL you want to upload your video on.
- **Zipline Token**: On Zipline UI, top-right corner, click on your name and click Copy token.
- **Blur's path**: Windows: Download the Windows-cli version of blur, and in File Explorer right click it and click copy path. MacOS: Same as linux, with the macOS cli installer. Linux: Download the tar.gz, then open it in you file manager, extract it, right-click the file named "blur" and copy path.
- **Blur's config path**: Path to a file that has a blur config in it. My blur config:
```ignorelang
[blur v2.42]
 
- blur
blur: true
blur amount: 1.2
blur output fps: 60
blur weighting: gaussian_sym
blur gamma: 1
 
- interpolation
interpolate: true
interpolated fps: 1005
interpolation method: svp
 
- deduplication
deduplicate: true
deduplicate method: svp
 
- rendering
encode preset: h264
quality: 12
preview: true
 
- gpu acceleration
gpu decoding: true
gpu interpolation: true
gpu encoding: false
```
These two will probably work with macOS too, I don't really know macOS.
- **Do you have obs systemd user service? (Linux only)**: This setting is only for Linux. Basically, the mod restarts this service when Minecraft gets launched. In the next option, I will explain it more.
- **What's the service's name? (Linux only)**: The user service's name. You can create this by running
```bash
mkdir -p ~/.config/systemd/user
nano ~/.config/systemd/user/obs.service
```
Then paste this in:
```ignorelang
[Unit]
Description=OBS Replay Buffer (User Daemon)
After=graphical-session.target
Wants=graphical-session.target

[Service]
Type=simple
ExecStart=/usr/bin/obs --startreplaybuffer --minimize-to-tray --disable-updates --multi
Restart=unless-stopped
RestartSec=5s

Environment=DISPLAY=:0

[Install]
WantedBy=graphical-session.target
```
CTRL + S to save, CTRL + X to leave.