#!/bin/bash
# Watch the GitHub Actions run(s) for one commit until every run concludes.
# Pass the FULL sha (a short sha matched nothing for an hour, 2026-09-12).
# Matches the sha CLIENT-SIDE over the latest runs: the API's head_sha filter
# returned nothing for dfe5ce991 and the watcher slept an hour.
SHA=$(git rev-parse "$1")
for i in $(seq 1 60); do
  r=$(curl -s "https://api.github.com/repos/neema2/legend-lite/actions/runs?per_page=12" \
      | python3 -c 'import sys,json
sha=sys.argv[1]
rs=[x for x in json.load(sys.stdin).get("workflow_runs",[]) if x["head_sha"]==sha]
print(" ".join("%s:%s:%s" % (x["name"], x["status"], x["conclusion"]) for x in rs) if rs else "none")' "$SHA")
  case "$r" in
    none|*in_progress*|*queued*|*pending*|*waiting*) sleep 60 ;;
    *) echo "CI $SHA $r"; exit 0 ;;
  esac
done
echo "CI $SHA timeout: $r"
