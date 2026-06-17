#!/usr/bin/env bash
#
# Fetch the frp client (frpc) and stage it as libfrpc.so for each Android ABI.
#
# The frpc binary powers remote access (the FRP reverse tunnel). It is NOT
# committed to the repo (~13-15 MB per ABI). Run this once before building if
# you need remote access; the app builds and runs fine without it (remote
# access simply won't start — see FrpTunnelManager).
#
# Android only executes binaries from the app's nativeLibraryDir, and they must
# be named lib*.so, so the frpc executable is renamed to libfrpc.so and dropped
# into src/main/jniLibs/<abi>/. With android:extractNativeLibs="true" it is
# extracted to nativeLibraryDir at install time and exec'd as a subprocess.
#
# Usage:  scripts/fetch-frpc.sh [FRP_VERSION]
# Example: scripts/fetch-frpc.sh 0.61.2
#
# Pick a version compatible with your frps. 0.61.2 is known to work with the
# PortTracker frp server. Any frpc >= 0.52 supports the TOML config this app
# generates.

set -euo pipefail

FRP_VERSION="${1:-0.61.2}"

# Map Android ABI -> frp release GOOS_GOARCH suffix.
# (frp does not publish a 32-bit linux_386 build, so x86 is omitted — it is
#  emulator-only and not needed for real devices.)
declare -A ABI_TO_GOARCH=(
  [arm64-v8a]=linux_arm64
  [armeabi-v7a]=linux_arm
  [x86_64]=linux_amd64
)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JNILIBS_DIR="$SCRIPT_DIR/../app/src/main/jniLibs"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "Fetching frpc v$FRP_VERSION -> $JNILIBS_DIR"

for abi in "${!ABI_TO_GOARCH[@]}"; do
  goarch="${ABI_TO_GOARCH[$abi]}"
  tarball="frp_${FRP_VERSION}_${goarch}.tar.gz"
  url="https://github.com/fatedier/frp/releases/download/v${FRP_VERSION}/${tarball}"

  echo "  - $abi  <-  $tarball"
  curl -fsSL -o "$TMP_DIR/$tarball" "$url"
  tar xzf "$TMP_DIR/$tarball" -C "$TMP_DIR"

  mkdir -p "$JNILIBS_DIR/$abi"
  cp "$TMP_DIR/frp_${FRP_VERSION}_${goarch}/frpc" "$JNILIBS_DIR/$abi/libfrpc.so"
done

echo "Done. Bundled libfrpc.so for: ${!ABI_TO_GOARCH[*]}"
