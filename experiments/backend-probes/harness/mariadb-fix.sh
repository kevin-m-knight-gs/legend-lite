#!/bin/bash
# Repairs the MariaDB4j macOS-ARM64 binaries, which are NOT self-contained:
# `mariadbd` dynamically links /opt/homebrew/opt/pcre2/lib/libpcre2-8.0.dylib,
# a Homebrew path that need not exist (it does not on this machine, and
# /opt/homebrew is owned by another user so `brew install pcre2` is not an option).
#
# DYLD_FALLBACK_LIBRARY_PATH does NOT solve this: MariaDB4j launches mariadbd via
# the SIP-protected /bin/sh wrapper `mariadb-install-db`, and macOS strips DYLD_*
# across that exec. The load path must therefore be rewritten into the binary.
#
# pcre2 10.47 was built from source into ../pcre2 (compat version 16.0.0, which
# satisfies mariadbd's required 14.0.0).
#
# Run this AFTER any run that re-extracts the binaries (MariaDB4j caches the base
# dir, so in practice once per machine is enough). Idempotent.

set -u
BASE="${MARIADB4J_BASE:-/var/folders/_m/77mrkjhs6k78qbh_wj6b1zdc0000gp/T/MariaDB4j/base}"
PC="/private/tmp/claude-502/-Users-neemsandv/9d0bca0a-c404-43ee-9bc6-4ed2e759ec31/scratchpad/pcre2/lib/libpcre2-8.0.dylib"

if [ ! -f "$PC" ]; then echo "pcre2 dylib missing at $PC" >&2; exit 1; fi
if [ ! -d "$BASE" ]; then echo "MariaDB4j base not extracted yet at $BASE (run the probe once first)" >&2; exit 1; fi

n=0
while IFS= read -r f; do
  if otool -L "$f" 2>/dev/null | grep -q "/opt/homebrew/opt/pcre2"; then
    install_name_tool -change /opt/homebrew/opt/pcre2/lib/libpcre2-8.0.dylib "$PC" "$f" 2>/dev/null
    codesign -s - -f "$f" >/dev/null 2>&1   # install_name_tool invalidates the signature; ad-hoc re-sign
    n=$((n+1)); echo "patched: $f"
  fi
done < <(find "$BASE" -type f \( -perm -u+x -o -name "*.dylib" -o -name "*.so" \) 2>/dev/null)
echo "patched $n binaries"
