#!/usr/bin/env bash
# .github/wait-for-ci.sh <commit> [workflow]: waits for CI (ci.yml, or the
# workflow named), run on a push of that commit, to finish, and succeeds only if
# every run of it passed. The release and the dev image wait on it (ported from
# Mandarin), so nothing reaches an install before every test has passed.
#
# CI red, then re-run to green: re-run the failed job here too, and it finds CI
# green this time.
set -u
sha=${1:?usage: .github/wait-for-ci.sh <commit> [workflow]}
wf=${2:-ci.yml}
pause=${WAIT_FOR_CI_PAUSE:-10}   # seconds between looks (the tests make it short)

for i in $(seq 1 360); do   # every 10 s, for an hour at most
  runs=$(gh run list --workflow "$wf" --commit "$sha" --event push --json status,conclusion,url \
    -q '.[] | "\(.status) \(.conclusion) \(.url)"') || runs=""
  if [ -n "$runs" ]; then
    if ! grep -qv '^completed ' <<<"$runs"; then
      echo "$runs"
      if grep -qv '^completed success ' <<<"$runs"; then
        echo "$wf didn't pass on $sha: nothing is published"
        exit 1
      fi
      echo "$wf passed on $sha"
      exit 0
    fi
  elif [ "$i" -gt 60 ]; then
    echo "No $wf run on $sha after 10 minutes"
    exit 1
  fi
  sleep "$pause"
done
echo "$wf on $sha still not finished after an hour"
exit 1
