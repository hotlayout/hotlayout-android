#!/usr/bin/env bash
# Builds the signed release into a local staging repository, zips it and uploads the bundle to the
# Central Portal. The upload is USER_MANAGED: the deployment waits on central.sonatype.com until it is
# published there by hand.
#
# Needs in ~/.gradle/gradle.properties:
#   signingInMemoryKey, signingInMemoryKeyPassword    the armored private key and its passphrase
#   mavenCentralUsername, mavenCentralPassword        a user token from central.sonatype.com
set -euo pipefail
cd "$(dirname "$0")/.."

prop() { grep -E "^$1=" "$HOME/.gradle/gradle.properties" 2>/dev/null | head -1 | cut -d= -f2- || true; }
user="${CENTRAL_USERNAME:-$(prop mavenCentralUsername)}"
pass="${CENTRAL_PASSWORD:-$(prop mavenCentralPassword)}"
[ -n "$user" ] && [ -n "$pass" ] || { echo "No Central token: set mavenCentralUsername and mavenCentralPassword." >&2; exit 1; }

staging=hotlayout/build/staging-deploy
rm -rf "$staging"
./gradlew --quiet :hotlayout:publishReleasePublicationToStagingRepository

version=$(grep -E '^version = ' hotlayout/build.gradle.kts | cut -d'"' -f2)
dir="$staging/com/hotlayout/hotlayout-android/$version"
for f in "$dir"/*.aar "$dir"/*.pom "$dir"/*.jar; do
  [ -f "$f.asc" ] || { echo "Not signed: $f (is signingInMemoryKey set?)" >&2; exit 1; }
done

bundle="hotlayout/build/central-bundle-$version.zip"
rm -f "$bundle"
(cd "$staging" && zip -qr "$OLDPWD/$bundle" com -x '*maven-metadata*')
echo "Bundle: $bundle"
unzip -l "$bundle"

auth=$(printf '%s:%s' "$user" "$pass" | base64)
id=$(curl --fail-with-body -sS -X POST \
  -H "Authorization: Bearer $auth" \
  -F "bundle=@$bundle" \
  "https://central.sonatype.com/api/v1/publisher/upload?name=hotlayout-android-$version&publishingType=USER_MANAGED")
echo "Deployment: $id"
echo "Check it and press Publish at https://central.sonatype.com/publishing/deployments"
