# shellcheck disable=SC2034
SKIPUNZIP=1
MIN_SDK=29
CONFIG_DIR=/data/adb/tricky_store
INTEGRITY_BOX=/data/adb/modules/playintegrityfix

# --- Installation Context Check ---
if [ -d "$INTEGRITY_BOX/toolkit" ]; then
  abort "! Install Integrity Box & reboot, then flash me"
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
  if ! unzip -qqjo "$ZIPFILE" "$1" -d "$2"; then
    abort "! Failed to extract $1"
  fi
  ui_print "- Extracted $1"
}

# --- Remove any conflicting modules ---
for remove_id in tricky_store oh_my_keymint teesim; do
    if [ -d "/data/adb/modules/$remove_id" ]; then
        rm -rf "/data/adb/modules/$remove_id/remove"
        ui_print "! $(grep_prop name "/data/adb/modules/$remove_id/module.prop") module will be removed on next reboot"
    fi
done

# --- Installation ---
ui_print "- Extracting module files"
for file in sepolicy.rule daemon; do
  install_file "$file" "$INTEGRITY_BOX"
done

# Handle attestation.apk or attestation.dex
if unzip -l "$ZIPFILE" | grep -q "attestation.apk"; then
  install_file "attestation.apk" "$INTEGRITY_BOX"
elif unzip -l "$ZIPFILE" | grep -q "attestation.dex"; then
  install_file "attestation.dex" "$INTEGRITY_BOX"
else
  abort "! Neither attestation.apk nor attestation.dex found"
fi

chmod 755 "$INTEGRITY_BOX/daemon"
ui_print ""


ui_print "- Extracting $ARCH libraries"
install_file "lib/$ABI_DIR/libTrickyStoreOSS.so" "$INTEGRITY_BOX"
install_file "lib/$ABI_DIR/libinject.so" "$INTEGRITY_BOX"
ui_print ""

mv "$INTEGRITY_BOX/libinject.so" "$INTEGRITY_BOX/inject"
chmod 755 "$INTEGRITY_BOX/inject"

# --- Configuration Files ---
if [ ! -d "$CONFIG_DIR" ]; then
  ui_print "- Creating configuration directory"
  mkdir -p "$CONFIG_DIR"
fi

ui_print ""
abort "! CI Update complete"