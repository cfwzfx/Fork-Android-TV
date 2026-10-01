#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/../.." && pwd)"
media_dir="${1:-$project_dir/.gradle/player-media-source}"
media_commit=3c2cbe8ac742c2fe15eff52f03eeb3b1b648848d
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_dir" && -f "$project_dir/local.properties" ]]; then
    sdk_dir="$(sed -n 's/^sdk.dir=//p' "$project_dir/local.properties" | head -n 1)"
fi
if [[ -z "$sdk_dir" || ! -d "$sdk_dir" ]]; then
    echo 'Set ANDROID_HOME to the Android SDK directory.' >&2
    exit 1
fi
if [[ ! -d "$media_dir/.git" ]]; then
    git clone --branch release-1.11.0-fongmi https://github.com/FongMi/media.git "$media_dir"
    git -C "$media_dir" checkout --detach "$media_commit"
fi
if [[ "$(git -C "$media_dir" rev-parse HEAD)" != "$media_commit" ]]; then
    echo "Media source must be at $media_commit; use a separate source directory." >&2
    exit 1
fi
patch_file="$project_dir/player-compat/patches/media-mpv-subtitle-config.patch"
if git -C "$media_dir" apply --check "$patch_file"; then
    git -C "$media_dir" apply "$patch_file"
elif ! git -C "$media_dir" apply --reverse --check "$patch_file"; then
    echo 'The subtitle configuration patch does not match this checkout.' >&2
    exit 1
fi
# Keep existing local build configuration, updating only sdk.dir.
local_file="$media_dir/local.properties"
if [[ -f "$local_file" ]]; then
    sed '/^sdk.dir=/d' "$local_file" > "$local_file.tmp"
else
    : > "$local_file.tmp"
fi
printf 'sdk.dir=%s\n' "$sdk_dir" >> "$local_file.tmp"
mv "$local_file.tmp" "$local_file"
tasks=()
while IFS= read -r aar || [[ -n "$aar" ]]; do
    [[ -z "$aar" ]] && continue
    tasks+=(":${aar%-release.aar}:assembleRelease")
done < "$media_dir/move.txt"
# Use the source wrapper (Gradle 9.1), independently of the App wrapper.
(cd "$media_dir" && bash gradlew "${tasks[@]}")
mkdir -p "$project_dir/app/libs"
while IFS= read -r aar || [[ -n "$aar" ]]; do
    [[ -z "$aar" ]] && continue
    artifact="$(find "$media_dir/libraries" -path "*/buildout/outputs/aar/$aar" -print -quit)"
    if [[ -z "$artifact" ]]; then
        echo "Missing artifact: $aar" >&2
        exit 1
    fi
    cp "$artifact" "$project_dir/app/libs/$aar"
done < "$media_dir/move.txt"
echo 'Player AARs rebuilt and copied to app/libs.'
