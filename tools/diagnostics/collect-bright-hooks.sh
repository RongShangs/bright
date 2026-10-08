#!/system/bin/sh
# Root-only, static firmware collection. No settings changes or logcat.
set -eu
umask 077
fail() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
safe_path() {
    case "$1" in /system/*|/system_ext/*|/product/*|/vendor/*|/odm/*|/data/app/*) ;; *) return 1 ;; esac
    case "$1" in *'/../'*|*'/./'*|*[!a-zA-Z0-9_./+=@,:~-]*) return 1 ;; esac
    case "$1" in *.jar|*.apk) return 0 ;; *) return 1 ;; esac
}
if [ "${1:-}" = '--self-test' ]; then
    safe_path /system/framework/services.jar || fail 'valid path rejected'
    safe_path '/data/app/~~abc==/pkg-123+/base.apk' || fail 'valid APK path rejected'
    for path in /data/user/0/private.apk /system/../data/private.apk '/system/x;id.jar' '/system/$(id).jar'; do
        if safe_path "$path"; then fail 'unsafe path accepted'; fi
    done
    printf '6 offline path checks passed. No device access.\n'
    exit 0
fi
apks_only=0
case "${1:-}" in --apks-only) apks_only=1 ;; '') ;; *) fail 'Supported options: --apks-only, --self-test' ;; esac
[ "$#" -le 1 ] || fail 'Too many arguments.'
[ "$(id -u)" = 0 ] || fail 'Run with Root (su), not ordinary Shell.'
[ -d /data/adb ] && [ ! -L /data/adb ] || fail '/data/adb is unavailable or is a symlink.'
for tool in getprop pm dumpsys settings find cp tar sha256sum mktemp stat od; do
    command -v "$tool" >/dev/null 2>&1 || fail "Required command unavailable: $tool"
done
[ -d /sdcard/Download ] || fail 'Unlock the phone and create the Download folder first.'
work=$(mktemp -d /data/adb/bright-diagnostics.XXXXXX) || fail 'Cannot create private staging directory.'
chmod 700 "$work"
bundle="$work/bundle"
meta="$bundle/metadata"
mkdir -p "$meta" "$bundle/firmware"
errors="$bundle/errors.txt"
manifest="$bundle/manifest.tsv"
: > "$errors"
printf 'sha256\tbytes\tdevice_path\n' > "$manifest"
printf '\n[1/4] Collecting device and LSP version metadata...\n'
{
    for key in ro.product.manufacturer ro.product.brand ro.product.model ro.product.device ro.build.version.release ro.build.version.sdk ro.build.version.incremental ro.build.version.security_patch ro.build.fingerprint ro.mi.os.version.name ro.mi.os.version.code ro.miui.ui.version.name ro.product.cpu.abilist; do
        printf '%s=%s\n' "$key" "$(getprop "$key")"
    done
    uname -r
} > "$meta/device.txt" 2>> "$errors"
{
    for prop in /data/adb/modules/*/module.prop; do
        [ -f "$prop" ] || continue
        if grep -qiE 'lsposed|lspd|xposed|zygisk|riru|vector' "$prop"; then
            printf '\n--- %s ---\n' "$prop"
            grep -E '^(id|name|version|versionCode)=' "$prop" || true
            if [ -f "${prop%/*}/disable" ]; then printf 'DISABLED\n'; fi
        fi
    done
} > "$meta/root-modules.txt" 2>> "$errors"
{
    printf 'system.subscreen_display_time='; settings get system subscreen_display_time
    printf 'secure.rear_doze_always_on='; settings get secure rear_doze_always_on
} > "$meta/rear-settings.txt" 2>> "$errors" || true
for manager in org.lsposed.manager org.lsposed.lspd org.meowcat.edxposed.manager; do
    dumpsys package "$manager" 2>> "$errors" | grep -E 'versionCode=|versionName=' > "$meta/$manager-version.txt" || true
done
inventory="$meta/framework-inventory.txt"
: > "$inventory"
if [ "$apks_only" -eq 0 ]; then
    for dir in /system/framework /system_ext/framework /product/framework /vendor/framework /odm/framework; do
        [ -d "$dir" ] || continue
        find "$dir" -type f -name '*.jar' >> "$inventory" 2>> "$errors" || true
    done
fi
targets="$work/targets.txt"
: > "$targets"
while IFS= read -r path; do
    safe_path "$path" || continue
    name=${path##*/}
    case "$name" in *services*.jar|miui*.jar|xiaomi*.jar|framework*.jar|ext.jar) printf '%s\n' "$path" >> "$targets" ;; esac
done < "$inventory"
# Filter on-device: never save the full installed app list or app-private data.
packages="$meta/selected-packages.txt"
pm list packages -s 2>> "$errors" | sed 's/^package://' | grep -Ei 'rear|sub.?screen|aod|systemui|powerkeeper|^com\.android\.settings$' | sort -u > "$packages" || true
while IFS= read -r pkg; do
    case "$pkg" in ''|*[!a-zA-Z0-9_.]*) continue ;; esac
    dumpsys package "$pkg" 2>> "$errors" | grep -E 'versionCode=|versionName=|codePath=' > "$meta/$pkg-version.txt" || true
    pm path "$pkg" 2>> "$errors" | sed 's/^package://' >> "$targets" || true
done < "$packages"
# PackageManager Binder calls can fail from some Root terminals. Read firmware paths
# directly as a fallback; never traverse /data/user, personal apps, or user storage.
apk_inventory="$work/system-apk-paths.txt"
: > "$apk_inventory"
for dir in /system/app /system/priv-app /system_ext/app /system_ext/priv-app /product/app /product/priv-app /vendor/app /vendor/priv-app /odm/app /odm/priv-app; do
    [ -d "$dir" ] || continue
    find "$dir" -type f -name '*.apk' >> "$apk_inventory" 2>> "$errors" || true
done
grep -Ei '/[^/]*(systemui|sub.?screen|rear|aod|powerkeeper|settings|aon)[^/]*/' "$apk_inventory" > "$meta/selected-apk-paths.txt" || true
cat "$meta/selected-apk-paths.txt" >> "$targets"
if [ ! -s "$packages" ]; then
    printf 'PackageManager returned no selected packages; firmware-path fallback used.\n' >> "$errors"
fi
if [ "$apks_only" -eq 1 ] && [ ! -s "$targets" ]; then
    # Filenames are non-personal system metadata and can help locate renamed components.
    cp "$apk_inventory" "$meta/system-apk-paths.txt"
    fail "No matching system APK found. Inventory retained at $meta/system-apk-paths.txt"
fi
sort -u "$targets" > "$work/targets-sorted.txt"
printf '[2/4] Copying firmware archives. Allow several minutes and sufficient free storage.\n'
count=0
while IFS= read -r path; do
    safe_path "$path" || continue
    [ -f "$path" ] || { printf 'Missing file: %s\n' "$path" >> "$errors"; continue; }
    dest="$bundle/firmware$path"
    mkdir -p "${dest%/*}"
    printf '  %s\n' "$path"
    if cp "$path" "$dest" 2>> "$errors"; then
        signature=$(od -An -tx1 -N2 "$dest" | tr -d ' \n')
        if [ "$signature" != 504b ]; then
            printf 'Not a ZIP/APK/JAR archive: %s\n' "$path" >> "$errors"
            mv "$dest" "$dest.incomplete"
            continue
        fi
        digest=$(sha256sum "$dest")
        bytes=$(stat -c %s "$dest")
        printf '%s\t%s\t%s\n' "${digest%% *}" "$bytes" "$path" >> "$manifest"
        count=$((count + 1))
    else
        printf 'Copy failed: %s\n' "$path" >> "$errors"
        [ ! -f "$dest" ] || mv "$dest" "$dest.incomplete"
    fi
done < "$work/targets-sorted.txt"
[ "$count" -gt 0 ] || fail "No archives extracted. Diagnostics retained in $work"
printf '[3/4] Writing report...\n'
cat > "$bundle/READ-ME.txt" <<EOF
Bright 2.0 static hook diagnostics
Goal: locate rear-display dark-environment timeout and proximity sleep policy.
Successful firmware files: $count
Included: selected system APK/JAR archives, build properties, relevant Root
module versions, selected system package versions, two rear-display settings.
Excluded: app-private data, full app list, full getprop, logcat, photos,
contacts, serial number, IMEI and Android ID.
Device settings were NOT changed. No reboot or module installation performed.
Read errors.txt for unavailable files. Empty manager-version files may be normal
when LSPosed uses a parasitic manager. Send its version screenshot separately.
Review metadata before sharing; do not publicly redistribute vendor firmware.
This static archive may require follow-up if firmware has no DEX or the target
implementation differs. It is not a compatibility guarantee.
EOF
printf '[4/4] Packing TAR.GZ...\n'
suffix=${work##*.}
kind=hook-info
[ "$apks_only" -eq 0 ] || kind=apks
name="bright-2.0-$kind-$(date +%Y%m%d-%H%M%S)-$suffix.tar.gz"
archive="$work/$name"
tar -czf "$archive" -C "$work" bundle || fail "Packing failed. Files retained in $work"
tar -tzf "$archive" >/dev/null || fail "Archive validation failed. Files retained in $work"
output="/sdcard/Download/$name"
[ ! -e "$output" ] && [ ! -L "$output" ] || fail "Output already exists: $output"
cp "$archive" "$output" || fail "Export failed; private archive retained in $work"
source_digest=$(sha256sum "$archive")
export_digest=$(sha256sum "$output")
[ "${source_digest%% *}" = "${export_digest%% *}" ] || fail 'Export checksum mismatch; private copy retained.'
# Delete only this invocation's validated private staging directory after verified export.
case "$work" in
    /data/adb/bright-diagnostics.??????)
        [ ! -L "$work" ] && [ "$(stat -c %u "$work")" = 0 ] || fail 'Unexpected staging directory; leaving it intact.'
        rm -rf -- "$work"
        ;;
    *) fail 'Unexpected staging path; cleanup skipped.' ;;
esac
printf '\nDONE: %s\nSHA-256: %s\nFiles: %s\n' "$output" "${export_digest%% *}" "$count"
printf 'Send this TAR.GZ plus phone/HyperOS and LSPosed version screenshots.\n'
