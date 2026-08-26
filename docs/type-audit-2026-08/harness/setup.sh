#!/bin/bash
# One-time setup: build the classpath the other two scripts use.
# Safe to re-run. Writes .cp next to these scripts.
set -e
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
cd "$ROOT"
echo "building core (this takes a few minutes the first time)..."
mvn -q -pl core -am test-compile -DskipTests
mvn -q -pl core org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
    -Dmdep.outputFile="$HERE/.deps" -Dmdep.includeScope=test
printf '%s\n' "$ROOT/core/target/classes:$ROOT/core/target/test-classes:$ROOT/core/src/test/resources:$(cat "$HERE/.deps")" > "$HERE/.cp"
javac -nowarn -cp "$(cat "$HERE/.cp")" -d "$HERE" "$HERE/Probe.java"
echo "ready. try:"
echo "  echo 'model::Person.all()->project(~[a:p|\$p.age])' > /tmp/q.pure"
echo "  $HERE/probe.sh $HERE/fixture/model.pure /tmp/q.pure test::TestRuntime $HERE/fixture/ddl.sql"
