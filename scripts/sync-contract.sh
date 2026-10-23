#!/bin/sh
# Copies the contract artifacts and the shared case tables from a checkout of the HotLayout
# contract into this project.
#
#   scripts/sync-contract.sh <path-to-contract-checkout>
#   HOTLAYOUT_CONTRACT=<path-to-contract-checkout> scripts/sync-contract.sh
#
# The path is the directory that holds `screen-schema.json`, `theme.json`, `icons.json`,
# `data-contract.json`, `placements.json` and `fixtures/`. There is no default.
#
# What it writes:
#  - hotlayout/src/test/resources/contract/*.json and fixtures/ — the artifacts and tables the
#    tests read (an exact copy);
#  - demo/src/main/assets/fixtures/ — the screen fixtures and themes the demo app opens, and
#    demo/src/main/assets/data-contract.json — the data dictionary whose sample images the demo shows;
#  - hotlayout/src/main/resources/com/hotlayout/icons.json — the icon set the reader ships with;
#  - hotlayout/src/main/resources/com/hotlayout/built-in-theme.json — the `contract` block of
#    theme.json, the theme the reader uses when the host passes none.
#
# The tests compare the shipped mirrors with the copied artifacts, so a sync that changes the
# contract without a matching change in the reader fails `./gradlew test`.
set -eu

here="$(cd "$(dirname "$0")/.." && pwd)"
contract="${1:-${HOTLAYOUT_CONTRACT:-}}"

if [ -z "$contract" ]; then
  echo "Usage: scripts/sync-contract.sh <path-to-contract-checkout> (or set HOTLAYOUT_CONTRACT)." >&2
  exit 1
fi
if [ ! -f "$contract/screen-schema.json" ]; then
  echo "No contract at $contract (screen-schema.json not found)." >&2
  exit 1
fi

tests="$here/hotlayout/src/test/resources/contract"
rm -rf "$tests"
mkdir -p "$tests"
cp "$contract"/*.json "$tests/"
cp -R "$contract/fixtures" "$tests/fixtures"

demo="$here/demo/src/main/assets/fixtures"
rm -rf "$demo"
mkdir -p "$demo"
cp "$contract"/fixtures/*.json "$demo/"
cp -R "$contract/fixtures/themes" "$demo/themes"
# The sample values of the data dictionary (the demo draws its pictures from them, offline).
cp "$contract/data-contract.json" "$here/demo/src/main/assets/data-contract.json"

cp "$contract/THIRD-PARTY-NOTICES.md" "$here/THIRD-PARTY-NOTICES.md"

library="$here/hotlayout/src/main/resources/com/hotlayout"
mkdir -p "$library"
cp "$contract/icons.json" "$library/icons.json"
python3 - "$contract/theme.json" "$library/built-in-theme.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as source:
    block = json.load(source)["contract"]
with open(sys.argv[2], "w", encoding="utf-8") as target:
    json.dump(block, target, ensure_ascii=False, indent=2)
    target.write("\n")
PY

echo "Synced contract from $contract"
