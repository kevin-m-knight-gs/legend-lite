#!/bin/bash
# One line per target dump: its name and the sha256 of its statements (the gunzipped text), so two containers' dumps
# compare without moving them: a sibling reports these lines, the session holding the baseline runs the same over it.
# usage: digests.sh <dump dir>
for f in "$1"/*.sql.gz; do echo "$(basename "$f" .sql.gz) $(gzip -dc "$f" | sha256sum | cut -c1-16)"; done
