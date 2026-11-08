#!/bin/sh
# The SDK's specs, run once before each release, as the TypeScript SDK's are: the build as
# scripts/gates.sh makes it, from nothing, with every spec under src/test/java run, the quickstart's
# walks among them. Maven runs offline, on what scripts/fetch.sh fetched, and in an environment
# holding PATH, HOME and JAVA_HOME alone, so that no credential in the shell that runs this reaches a
# spec; a spec says so, by every variable shaped like one. No spec reaches a network: each is
# answered by a stand-in of the API, the HTTP client the SDK is given, in the spec's own process.
set -eu
cd "$(dirname "$0")/.."

rm -rf target
exec env -i PATH="$PATH" HOME="$HOME" JAVA_HOME="${JAVA_HOME:-}" \
  mvn --offline --batch-mode --settings scripts/maven-settings.xml package
