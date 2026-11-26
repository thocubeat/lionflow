#!/bin/bash
# Says once a minute how the machine is doing, on a branch of its own: what the job last wrote, and what is using
# the memory and the processor. For a job that stops without leaving a log behind (a machine that runs out of
# memory loses touch, and its log with it). Started in the background by a workflow:
#
#   ios-progress.sh <branch> <log file>...
#
# It also stops the app in the simulator if that ever takes more than 2 GB of memory, so the machine survives to
# tell what happened.
branch="$1"; shift
work="$(mktemp -d)"
cd "$work" || exit 0
git init -q -b "$branch"
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
while true; do
  {
    echo "$(date -u '+%Y-%m-%d %H:%M:%S') UTC, commit ${GITHUB_SHA:-?}"
    echo "load: $(sysctl -n vm.loadavg 2>/dev/null)   disk: $(df -h / | tail -n 1 | awk '{ print $4 " free of " $2 }')"
    vm_stat 2>/dev/null | sed -n 2,5p
    echo "--- most memory"
    ps -axo rss=,pcpu=,pid=,comm= | sort -rn | head -n 10 | awk '{ printf "%6d MB %6s%% %7s %s\n", $1 / 1024, $2, $3, $4 }'
    echo "--- most processor"
    ps -axo pcpu=,rss=,pid=,comm= | sort -rn | head -n 8 | awk '{ printf "%6s%% %6d MB %7s %s\n", $1, $2 / 1024, $3, $4 }'
    ps -axo rss=,pid=,comm= | awk '$1 > 2000000 && $3 ~ /LionFlow\.app\/LionFlow$/ { print $1, $2 }' | while read -r rss pid; do
      echo "!!! the app (pid $pid) uses $((rss / 1024)) MB of memory: stopped"
      kill -9 "$pid"
    done
    for log in "$@"; do
      echo "--- $(basename "$log")"
      tail -n 40 "$log" 2>/dev/null | cut -c1-300
    done
  } > progress.txt 2>&1
  git add -A > /dev/null 2>&1
  git commit -q -m "$(date -u '+%H:%M:%S')" > /dev/null 2>&1
  git push -q --force "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" "$branch" > /dev/null 2>&1
  sleep 45
done
