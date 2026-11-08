#!/bin/sh
# Fetches what the SDK's build needs and this machine's Maven cache, ~/.m2/repository, lacks, from
# Maven Central alone: scripts/maven-settings.xml mirrors every repository to
# https://repo.maven.apache.org/maven2, and --strict-checksums refuses a file whose checksum doesn't
# match Central's. It builds the SDK as scripts/gates.sh does, without the gate's checks and running
# no spec, so that the gate and scripts/test.sh run offline after it. Then it fetches the plugin that
# runs the quickstart, which the build doesn't bind, by its help, which runs nothing else. Run it
# again whenever a version in pom.xml changes.
set -eu
cd "$(dirname "$0")/.."

rm -rf target
mvn --batch-mode --strict-checksums --settings scripts/maven-settings.xml -DskipTests package
mvn --batch-mode --quiet --strict-checksums --settings scripts/maven-settings.xml exec:help
