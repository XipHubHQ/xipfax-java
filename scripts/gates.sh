#!/bin/sh
# The repository's gate, run before every pull request: the SDK as openapi.json generates it, its
# version and its package as pom.xml names them, the build, from nothing, which compiles the SDK, its
# specs and the quickstart for Java 11 and packs the SDK with its sources and its Javadoc, and the
# quickstart's run, with no key. The specs run once before each release, by scripts/test.sh, not
# here. Maven runs offline, on what scripts/fetch.sh fetched: a file the cache lacks stops the gate,
# named.
set -eu
cd "$(dirname "$0")/.."

sh scripts/generate.sh

# What the generator writes, by the list it wrote now and the one the commit holds, and ApiVersion.java
# in whichever package: a generated file that differs from the commit, is new to it or is gone from it
# fails the gate.
generated=$({
  cat .openapi-generator/FILES
  git show HEAD:.openapi-generator/FILES 2>/dev/null || true
} | grep -E '^src/main/java/.+\.java$' | sort -u)
changed=$(git status --porcelain --untracked-files=all -- 'src/main/java/*/ApiVersion.java' .openapi-generator $generated)
if [ -n "$changed" ]; then
  echo "The SDK differs from what openapi.json generates: run scripts/generate.sh and commit it." >&2
  echo "$changed" >&2
  exit 1
fi

# The version the generator writes as Configuration.VERSION, held to the one Maven publishes.
generated_version=$(sed -n 's/^artifactVersion: //p' openapi-generator.yaml)
built_version=$(xmllint --xpath "string(/*[local-name()='project']/*[local-name()='version'])" pom.xml)
if [ -z "$generated_version" ] || [ "$generated_version" != "$built_version" ]; then
  echo "openapi-generator.yaml's artifactVersion, $generated_version, isn't pom.xml's version, $built_version: name the release in both." >&2
  exit 1
fi

# The package the generator writes the SDK in, held to the name the jar gives Java's modules.
package=$(sed -n 's/^invokerPackage: //p' openapi-generator.yaml)
module=$(xmllint --xpath "string(//*[local-name()='Automatic-Module-Name'])" pom.xml)
if [ -z "$package" ] || [ "$package" != "$module" ]; then
  echo "openapi-generator.yaml's invokerPackage, $package, isn't the module name pom.xml gives the jar, $module: name the package in both." >&2
  exit 1
fi

# From nothing, so that a class the document no longer makes isn't packed.
rm -rf target
mvn --offline --batch-mode --quiet --settings scripts/maven-settings.xml -DskipTests package

# The quickstart as the README runs it, in an environment holding PATH, HOME and JAVA_HOME alone, so
# that it has no key: it says where a key is made and ends with exit status 1, having sent nothing.
# That proves the run finds the quickstart and the classes it takes.
said=$(env -i PATH="$PATH" HOME="$HOME" JAVA_HOME="${JAVA_HOME:-}" \
  mvn --offline --batch-mode --quiet --settings scripts/maven-settings.xml test-compile exec:java 2>&1) &&
  ran=0 || ran=$?
if [ "$ran" -ne 1 ] || ! printf '%s\n' "$said" | grep -q -F 'Set XIPFAX_API_KEY to a key you made in the developer console'; then
  echo "The quickstart doesn't run as the README says, its exit status $ran:" >&2
  printf '%s\n' "$said" >&2
  exit 1
fi
echo "Gates: GREEN"
