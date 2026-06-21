# kidstv — Pi Setup & Laptop Workflow

Two parts: a one-time Pi setup, and the recurring laptop workflow.

The Pi is a dumb playback appliance. It holds no library, no database, no `kidstv` binary. It receives a folder of videos + a playlist from your laptop, plays them fullscreen on boot, then shuts down. All the actual CMS work happens on the laptop.

---

## Part 1: One-time Pi setup

### Hardware

- Raspberry Pi 4 (4GB recommended; 2GB works)
- Official Pi power supply
- MicroSD card, 64GB, A2-rated (SanDisk Extreme or Samsung Evo Plus)
- Micro-HDMI to HDMI cable
- Case with passive cooling (Argon NEO or FLIRC)
- Smart plug (TP-Link Tapo P100 or Kasa equivalent)

### 1. Flash the SD card

Download **Raspberry Pi Imager** from raspberrypi.com/software. Insert the SD card. In Imager:

- Choose OS → **Raspberry Pi OS Lite (64-bit)**
- Choose Storage → your SD card
- Click the gear icon and set:
    - Hostname: `kidstv`
    - Enable SSH with a username (`pi`) and password
    - Configure Wi-Fi (SSID + password)
    - Set locale to GB

Write the image. Pop the SD card into the Pi, plug in HDMI and power. Give it 2 minutes to boot.

### 2. SSH in from your laptop

```bash
ssh pi@kidstv.local
```

If `.local` doesn't resolve, find the Pi's IP in your router admin and use that.

### 3. Install mpv

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y mpv rsync
```

That's the only software the Pi needs. No JVM, no scala-cli, no `kidstv` binary.

### 4. Create the playback directory

```bash
mkdir -p ~/kidstv-current
```

This is where your laptop will rsync the weekend's content. Empty for now.

### 5. The playback script

```bash
nano ~/play.sh
```

Paste:

```bash
#!/bin/bash
# Wait for the display to be ready
sleep 5

# Look for a playlist file; fall back to playing the folder if none exists
if [ -f ~/kidstv-current/playlist.m3u ]; then
    mpv \
        --fullscreen \
        --no-osc \
        --no-input-default-bindings \
        --no-input-cursor \
        --really-quiet \
        --loop-playlist=no \
        --playlist=~/kidstv-current/playlist.m3u 2>/dev/null
else
    # Fallback: shuffle whatever's in the folder
    mpv \
        --fullscreen \
        --no-osc \
        --no-input-default-bindings \
        --no-input-cursor \
        --really-quiet \
        --shuffle \
        --loop-playlist=no \
        ~/kidstv-current/*.mp4 ~/kidstv-current/*.mkv ~/kidstv-current/*.webm 2>/dev/null
fi

# Clean shutdown when playback ends
sudo shutdown -h now
```

Save (Ctrl+O, Enter, Ctrl+X) and make it executable:

```bash
chmod +x ~/play.sh
```

### 6. Let the script shut down without a password

```bash
sudo visudo
```

Add at the bottom:

```
pi ALL=(ALL) NOPASSWD: /sbin/shutdown
```

### 7. Auto-launch on boot

```bash
sudo nano /etc/systemd/system/kidstv.service
```

Paste:

```ini
[Unit]
Description=Kids TV Playback
After=multi-user.target

[Service]
Type=simple
User=pi
ExecStart=/home/pi/play.sh
StandardOutput=journal
StandardError=journal
TTYPath=/dev/tty1
StandardInput=tty

[Install]
WantedBy=multi-user.target
```

Enable it:

```bash
sudo systemctl enable kidstv.service
```

### 8. Smart plug schedule

In the Tapo or Kasa app:

- **Saturday 7:00am**: turn on
- **Saturday 7:50am**: turn off (backstop in case the script hangs)
- Same for Sunday

The Pi boots when power arrives, plays through, shuts down via the script. The plug cuts power 5 minutes later as a safety net.

### 9. Set up passwordless SSH from your laptop

So you can rsync without typing a password every time. From your laptop:

```bash
ssh-copy-id pi@kidstv.local
```

(If you don't already have an SSH key, run `ssh-keygen -t ed25519` first, accepting defaults.)

### 10. Test the pipeline

Drop a single video onto the Pi to verify:

```bash
# From your laptop
scp test-video.mp4 pi@kidstv.local:~/kidstv-current/
ssh pi@kidstv.local sudo reboot
```

The Pi should boot, play the video fullscreen, then shut down. If that works, you're done with Pi setup forever (until the SD card dies).

---

## Part 2: Laptop workflow

All your library management happens on the laptop. The Pi only ever receives the output of `build-session`.

### Repository layout

```
~/code/kidstv/           # your scala-cli project
├── src/...
├── library.db           # SQLite, lives here
├── videos/              # downloaded files
├── prompts/extract_tags.md
└── current-session/     # generated each weekend, rsync'd to Pi
```

### Initial config

Make sure your shell has:

```bash
export ANTHROPIC_API_KEY="sk-ant-..."
```

in `~/.zshrc` or `~/.bashrc` so the enrichment step can reach the API.

### Adding new content (do this whenever you spot something good)

Throughout the week, drop URLs into a notes file or just run as you find them:

```bash
cd ~/code/kidstv
kidstv add https://youtube.com/watch?v=...
kidstv add https://youtube.com/watch?v=...
```

No need to batch. Adds are cheap and don't download files yet.

### Friday night: enrich and review

```bash
kidstv enrich --all-candidates
```

Pulls metadata from external sources and runs each candidate through the LLM for tag extraction. Takes a minute per video or so.

```bash
kidstv sync
```

Downloads the actual video files for any Candidate that doesn't have one yet (so you can watch during review).

```bash
kidstv review
```

Interactive loop. Each candidate plays via `mpv`; you press `a` to approve, `r` to reject (with reason), `t` to override tags, `q` to stop. Do this with a cup of tea, takes 5-10 minutes for a handful of candidates.

### Friday night or Saturday morning: build the session

```bash
kidstv build-session --duration 45m --mood calm --output current-session/
```

Produces `current-session/playlist.m3u` plus symlinks to the actual video files. Inspect if you want — it's just text and files.

### Push to the Pi

```bash
rsync -av --delete --copy-links current-session/ pi@kidstv.local:~/kidstv-current/
```

The `--copy-links` flag dereferences the symlinks so the Pi gets real files. `--delete` removes last weekend's content. After this runs, the Pi's `~/kidstv-current/` exactly mirrors your session folder.

### Wrap it in a script

Put this in `~/code/kidstv/publish.sh`:

```bash
#!/bin/bash
set -e

DURATION="${1:-45m}"
MOOD="${2:-calm}"

echo "Building session: $DURATION, mood: $MOOD"
kidstv build-session --duration "$DURATION" --mood "$MOOD" --output current-session/

echo "Pushing to Pi..."
rsync -av --delete --copy-links current-session/ pi@kidstv.local:~/kidstv-current/

echo "Done. Pi will play on next boot."
```

`chmod +x publish.sh`. Now Friday's command is just:

```bash
./publish.sh 45m calm
```

### Sanity check before bed

```bash
ssh pi@kidstv.local ls ~/kidstv-current/
```

Should show the videos and `playlist.m3u`. If yes, smart plug will do the rest at 7am.

### Mid-weekend changes

If she wants something specific on Sunday that wasn't in Saturday's session, just rebuild and push again:

```bash
./publish.sh 30m energetic
```

Pi picks up new content on next boot.

### Backups

The whole library is in the git repo. Commit periodically:

```bash
git add library.db prompts/ src/
git commit -m "Library state: $(date +%Y-%m-%d)"
```

(Add `videos/` and `current-session/` to `.gitignore` — those are reproducible from the URLs in the database.)

If the laptop dies, you clone the repo on a new machine, run `kidstv sync` to re-download the videos, and you're back. If the Pi dies, you reflash an SD card with the steps in Part 1.

---

## Quick reference

**One-time, on the Pi:**
- Flash OS Lite, set up SSH, install mpv, create play.sh + systemd service, smart plug schedule

**Weekly, on the laptop:**
- `kidstv add <url>` (whenever)
- `kidstv enrich --all-candidates` (Friday)
- `kidstv review` (Friday)
- `./publish.sh 45m calm` (Friday night)

**The Pi just turns on Saturday at 7am, plays, turns off.**