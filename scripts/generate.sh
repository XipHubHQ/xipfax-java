#!/bin/sh
# Makes the SDK from openapi.json, the API's document: scripts/sdk-document.jq writes the document
# it is generated from, openapi-generator 7.17.0 generates the client from that with
# openapi-generator.yaml, and this script writes ApiVersion.java from the document's info.version.
# Nothing it writes is edited by hand. It fetches nothing.
set -eu
cd "$(dirname "$0")/.."

generator=$(openapi-generator version)
if [ "$generator" != "7.17.0" ]; then
  echo "scripts/generate.sh takes openapi-generator 7.17.0, and this machine has $generator." >&2
  exit 1
fi

if ! version=$(jq -er '.info.version | select(test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"))' openapi.json); then
  echo "openapi.json's info.version is not a day, as 2026-11-03 is." >&2
  exit 1
fi

# The SDK's Java package, which openapi-generator.yaml names alone.
if ! package=$(sed -n 's/^invokerPackage: //p' openapi-generator.yaml | grep -E -x '[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+'); then
  echo "openapi-generator.yaml's invokerPackage is not one Java package, as com.xiphub.xipfax.sdk is." >&2
  exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
jq -f scripts/sdk-document.jq openapi.json > "$work/openapi.json"

# The Java files the last run wrote go first, so that one the document no longer makes doesn't stay,
# and ApiVersion.java, this script's, wherever a package before put it.
if [ -f .openapi-generator/FILES ]; then
  grep -E '^src/main/java/.+\.java$' .openapi-generator/FILES | while IFS= read -r file; do
    rm -f "./$file"
  done
fi
if [ -d src/main/java ]; then
  find src/main/java -name ApiVersion.java -type f -exec rm -f {} +
fi

openapi-generator generate -g java -i "$work/openapi.json" -o . -c openapi-generator.yaml \
  --api-package "$package.api" --model-package "$package.model"

cat > "src/main/java/$(echo "$package" | tr . /)/ApiVersion.java" <<EOF
// Written by scripts/generate.sh from openapi.json's info.version. Do not edit.

package $package;

/**
 * The version of XipFax's API this SDK is generated from, which every request names in its
 * Xip-Version header.
 */
public final class ApiVersion {
  /** The version, the day the API's document names it by. */
  public static final String API_VERSION = "$version";

  private ApiVersion() {}
}
EOF
