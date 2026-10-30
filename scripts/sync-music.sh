#!/usr/bin/env bash
# Copy a rekordbox music folder (and the collection.xml inside it) to a connected
# Android device, incrementally, then let the media scanner see the files.
#
#   scripts/sync-music.sh [SOURCE] [DESTINATION]
#
#   SOURCE       default: ~/Music/dj songs
#   DESTINATION  default: /sdcard/Music/dj songs
#
#   ANDROID_SERIAL=...       pick a device when several are attached
#   XML=/path/to/export.xml  push that file as collection.xml after the folder
#
# Only files whose size/mtime differ from the device copy are sent (adb push
# preserves both). Destination is under /sdcard so the Storage Access Framework,
# every file manager and FLUX's "＋ Folder" see the same tree on each run.
set -euo pipefail

SRC="${1:-$HOME/Music/dj songs}"
DEST="${2:-/sdcard/Music/dj songs}"
XML="${XML:-}"

die() { echo "sync-music: $*" >&2; exit 1; }

command -v adb >/dev/null 2>&1 || die "adb not found — install Android platform-tools"
[ -d "$SRC" ] || die "no such folder: $SRC"
if [ -n "$XML" ] && [ ! -f "$XML" ]; then die "no such xml: $XML"; fi
[ -f "$SRC/collection.xml" ] && HAVE_XML=1 || HAVE_XML=0

ADB=(adb)
if [ -n "${ANDROID_SERIAL:-}" ]; then ADB=(adb -s "$ANDROID_SERIAL"); fi

online=$("${ADB[@]}" devices | awk 'NR > 1 && $2 == "device" { n++ } END { print n + 0 }')
[ "$online" -gt 0 ] || die "no authorised device — check 'adb devices' and the USB debugging prompt"
[ "$online" -eq 1 ] || die "several devices attached — set ANDROID_SERIAL"

"${ADB[@]}" shell "mkdir -p '$DEST'"

# Android's shared storage on MIUI/HyperOS refuses : ? * | < > \ and " in file
# names, so those become "-". Rekordbox.norm() drops punctuation, so the import
# still matches by file stem. Only changed files are pushed.
python3 - "$SRC" "$DEST" "${ADB[@]}" <<'PY'
import os, subprocess, sys

src, dest, *adb = sys.argv[1:]
bad = set(':?*|<>\\"')

def run(*args):
    return subprocess.run(adb + list(args), capture_output=True, text=True)

listing = run("shell", f"cd '{dest}' 2>/dev/null && stat -c '%s %Y %n' -- *").stdout
remote = {}
for line in listing.splitlines():
    size, mtime, name = line.split(" ", 2)
    remote[name] = (int(size), int(mtime))

local, targets = [], set()
for name in sorted(os.listdir(src)):
    path = os.path.join(src, name)
    if name.startswith(".") or not os.path.isfile(path):
        continue
    st = os.stat(path)
    stem, ext = os.path.splitext("".join("-" if c in bad else c for c in name))
    target, n = stem + ext, 2
    while target in targets:
        target, n = f"{stem} ({n}){ext}", n + 1
    targets.add(target)
    local.append((name, target, st.st_size, int(st.st_mtime)))

todo = [(name, target) for name, target, size, mtime in local if remote.get(target) != (size, mtime)]
print(f"{len(local)} files on the machine, {len(todo)} to send")
for i, (name, target) in enumerate(todo, 1):
    if target != name:
        print(f"  device-safe name: {name} -> {target}")
    result = run("push", os.path.join(src, name), f"{dest}/{target}")
    if result.returncode != 0:
        sys.exit(f"push failed for {name}: {result.stderr.strip()}")
    print(f"  [{i}/{len(todo)}] {target}")
PY

if [ -n "$XML" ]; then
    echo "pushing $XML as collection.xml"
    "${ADB[@]}" push "$XML" "$DEST/collection.xml"
elif [ "$HAVE_XML" -eq 0 ]; then
    echo "note: no collection.xml in $SRC — audio only, no grids/cues/playlists" >&2
fi

# macOS metadata is noise on the tablet
"${ADB[@]}" shell "find '$DEST' -name '.DS_Store' -delete" >/dev/null 2>&1 || true

# Make the files visible to MediaStore so the Local source lists them.
"${ADB[@]}" shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 ||
    "${ADB[@]}" shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file://$DEST'" >/dev/null 2>&1 || true

echo
echo "done. In FLUX: Library → ＋ Folder → Music → \"$(basename "$DEST")\"."
echo "That folder carries the audio and collection.xml, so one pick imports both."
