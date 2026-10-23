# shellcheck disable=SC2034
SKIPUNZIP=1
MIN_SDK=29
CONFIG_DIR=/data/adb/tricky_store
INTEGRITY_BOX=/data/adb/modules/playintegrityfix
BOOT=/data/adb/service.d

# --- Installation Context Check ---
if [ ! -d "$INTEGRITY_BOX/toolkit" ]; then
  abort "! Integrity Box is not installed or has not completed its first boot. Install Integrity Box and reboot, then flash this CI updater."
fi

if [ "$BOOTMODE" != true ]; then
  ui_print "! Please install in Magisk Manager or KernelSU Manager"
  abort "! Install from recovery is NOT supported"
fi

if [ "$KSU" = true ] && [ "$KSU_VER_CODE" -lt 10670 ]; then
  abort "! Please update your KernelSU and KernelSU Manager"
fi

# --- Version Info ---
ui_print "- Installing Tricky Store OSS $(grep_prop version "$TMPDIR/module.prop")"
ui_print ""

# --- Architecture Handling ---
case "$ARCH" in
  arm64) ABI_DIR="arm64-v8a" ;;
  arm)   ABI_DIR="armeabi-v7a" ;;
  x64)   ABI_DIR="x86_64" ;;
  x86)   ABI_DIR="x86" ;;
  *)     abort "! Unsupported architecture: $ARCH" ;;
esac

ui_print "- Device platform: $ARCH"
ui_print "- Using ABI dir: $ABI_DIR"

# --- SDK Check ---
if [ "$API" -lt "$MIN_SDK" ]; then
  abort "! Unsupported SDK: $API. Minimum required is $MIN_SDK"
else
  ui_print "- Device SDK: $API"
fi
ui_print ""

# --- Helper to install files ---
install_file() {
  source_file="$1"
  destination_dir="$2"

  if [ ! -f "$ZIPFILE" ]; then
    abort "! Update ZIP not found: $ZIPFILE"
  fi

  if ! mkdir -p "$destination_dir"; then
    abort "! Cannot create destination: $destination_dir"
  fi

  if ! unzip -o -qq -j "$ZIPFILE" "$source_file" -d "$destination_dir"; then
    abort "! Failed to extract $source_file to $destination_dir"
  fi

  ui_print "- Extracted $source_file"
}

# --- Remove any conflicting modules ---
for dir in \
    /data/adb/modules/tricky_store \
    /data/adb/modules_update/tricky_store \
    /data/adb/modules/oh_my_keymint \
    /data/adb/modules_update/oh_my_keymint \
    /data/adb/modules/teesim \
    /data/adb/modules_update/teesim
do
    [ -d "$dir" ] && rm -rf "$dir"
done

# --- Installation ---
ui_print "- Preparing Installer"
for file in customize.sh module.prop; do
  install_file "$file" "$MODPATH"
done

ui_print "- Updating sepolicy.rule & daemon"
for file in sepolicy.rule daemon; do
  install_file "$file" "$INTEGRITY_BOX"
  chmod 755 "$INTEGRITY_BOX/daemon"
done

ui_print "- Updating boot script"
for file in tricky.sh; do
  install_file "$file" "$BOOT"
  chmod 755 "$BOOT/tricky.sh"
done

if unzip -l "$ZIPFILE" | grep -E '[[:space:]]attestation\.dex$' >/dev/null; then
  install_file "attestation.dex" "$INTEGRITY_BOX"
else
  abort "! attestation.dex not found in update ZIP"
fi

ui_print "- Extracting $ARCH libraries"
install_file "lib/$ABI_DIR/libTrickyStoreOSS.so" "$INTEGRITY_BOX"
install_file "lib/$ABI_DIR/libinject.so" "$INTEGRITY_BOX"
ui_print ""

ui_print "- Updating injector"
mv "$INTEGRITY_BOX/libinject.so" "$INTEGRITY_BOX/inject"
chmod 755 "$INTEGRITY_BOX/inject"

# --- Configuration Files ---
if [ ! -d "$CONFIG_DIR" ]; then
  ui_print "- Creating configuration directory"
  mkdir -p "$CONFIG_DIR"
fi

ui_print ""
ui_print "- CI Update complete"
ui_print "- Please reboot to apply the update"
