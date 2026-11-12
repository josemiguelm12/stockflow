#!/bin/sh
set -eu
interval="${STOCKFLOW_WORKER_INTERVAL_SECONDS:-15}"
case "$interval" in ''|*[!0-9]*) interval=15;; esac
trap 'exit 0' INT TERM
while :; do
  java -jar /opt/stockflow/app.jar || echo "Outbox worker invocation failed; retrying after interval" >&2
  sleep "$interval" & wait $!
done
