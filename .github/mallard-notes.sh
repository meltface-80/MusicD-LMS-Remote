#!/usr/bin/env bash
# .github/mallard-notes.sh [all] < changelog-lines: the notes Mallard's update offer shows (mallard.json's "notes").
# The changelog's entries ("- Added - …"), only those that name Mallard unless "all" (a Mallard release's own section
# is all Mallard), as many WHOLE entries as fit in 1200 characters, never one cut mid-sentence.
set -euo pipefail
awk -v all="${1:-}" '
  /^- / && (all == "all" || tolower($0) ~ /mallard/) {
    if (n + length($0) + 1 > 1200) exit
    out = out (n ? "\n" : "") $0; n += length($0) + 1
  }
  END { if (n) print out }'
