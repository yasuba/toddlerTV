# kidstv

A CLI tool for maintaining a curated library of pre-vetted video content for a preschooler, and assembling it into "session" playlists that play back like a TV channel — fixed duration, no algorithms, no autoplay.

## Requirements

- [scala-cli](https://scala-cli.virtuslab.org/)
- [yt-dlp](https://github.com/yt-dlp/yt-dlp)
- [mpv](https://mpv.io/) (for review playback)
- `ANTHROPIC_API_KEY` environment variable (for tag enrichment)

## Usage

```bash
# Add videos to the library (fetches metadata, does not download yet)
scala-cli run . -- add "https://www.youtube.com/watch?v=..."

# Enrich with structured tags via LLM
scala-cli run . -- enrich --all-candidates
scala-cli run . -- enrich --id bluey-keepy-uppy

# Interactively review candidates (plays via mpv, then approve/reject/defer)
scala-cli run . -- review

# Download approved videos, delete rejected/retired ones
scala-cli run . -- sync

# Build a session playlist
scala-cli run . -- build-session --duration 30m
scala-cli run . -- build-session --duration 45m --mood calm --output current-session/

# Play it
mpv --playlist=current-session.m3u --fullscreen

# Browse the library
scala-cli run . -- list
scala-cli run . -- list --status approved --show bluey

# Retire a video (no longer eligible for sessions)
scala-cli run . -- retire bluey-s01e01 --reason "seen too many times"
```

## Data

Everything lives in the project directory:

- `library.db` — SQLite database (gitignored)
- `videos/` — downloaded files (gitignored)
- `current-session.m3u` — last generated playlist
