#!/bin/bash
# Bazel's downloader gets 404 from Maven Central through this session's proxy while curl gets the file: seed the
# distdir (which Bazel reads first, and checks against the pinned sha256) with every URL a fetch reports as failed,
# round after round, until a fetch reports none (Bazel names the next failures only after the previous are there).
export PATH=/root/.local/bin:$PATH
dist=${LEGEND_DISTDIR:-$HOME/.cache/legend-lite/distdir}
mkdir -p "$dist"
targets="$@"
for round in $(seq 1 40); do
  log=$(mktemp)
  bazel fetch --keep_going $targets > "$log" 2>&1
  urls=$(grep -o "Error downloading \[[^]]*\]" "$log" | sed 's/Error downloading \[//; s/\]$//' | sort -u)
  if [ -z "$urls" ]; then
    echo "round $round: nothing failed"; rm -f "$log"; exit 0
  fi
  n=0
  for u in $urls; do
    f=$(basename "$u")
    if [ ! -s "$dist/$f" ]; then
      code=$(curl -sS -o "$dist/$f" -w "%{http_code}" --max-time 300 "$u")
      if [ "$code" != "200" ]; then rm -f "$dist/$f"; echo "  $code $u"; else n=$((n+1)); fi
    fi
  done
  echo "round $round: seeded $n of $(echo "$urls" | wc -l) failed urls"
  rm -f "$log"
done
