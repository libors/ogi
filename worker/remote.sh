#!/bin/bash
# Runs one harness main class on the remote worker, from the Mac (docs/build-and-run.md, "Remote worker"):
# syncs the bundle, builds the worker image if its inputs changed, runs the container and syncs the results back.
#
# Usage: worker/remote.sh <host> <bundle dir> <results dir> <name> <main class> [argument...]
#   <name>   the run's directory under ~/ogi/out on the worker (/ogi/out/<name> in the container) and under the
#            results directory on the Mac
#   OGI_JVM_OPTIONS                    extra JVM options for the run
#   OGI_ROBOCODE_VERSION, OGI_ROBOCODE_SHA256   the Robocode setup jar in the bundle's worker/ directory
#   OGI_CPUSET                         cores the container may use, e.g. 0-1 (default: all); a set, not a quota,
#                                      because a CPU quota pauses the container in bursts, and robots skip turns
#   OGI_MEMORY                         container memory limit (default 3g)
#   OGI_BATTLE_CORE                    the core for the battle's threads (worker/run.sh); default: not pinned
#   OGI_CLEAN                          if set, the run's directory on the worker is emptied first
set -euo pipefail

host=$1
bundle=$2
results=$3
name=$4
shift 4
# The name becomes a directory that a clean run deletes: letters, digits, dots, dashes and underscores only.
[[ $name =~ ^[A-Za-z0-9._-]+$ ]] || { echo "remote.sh: bad run name '$name'" >&2; exit 2; }

# The image depends only on the Dockerfile and the Robocode setup jar.
setup="robocode-$OGI_ROBOCODE_VERSION-setup.jar"
tag=$(cat "$bundle/worker/Dockerfile" "$bundle/worker/$setup" | shasum -a 256 | cut -c1-12)

rsync -az --delete "$bundle/" "$host:ogi/bundle/"
ssh "$host" "docker image inspect ogi-worker:$tag >/dev/null 2>&1 || docker build --quiet \
    --build-arg ROBOCODE_VERSION=$OGI_ROBOCODE_VERSION --build-arg ROBOCODE_SHA256=$OGI_ROBOCODE_SHA256 \
    -t ogi-worker:$tag ogi/bundle/worker"

# Hardening: no network, read-only root, no capabilities, our bundle and the foreign jars read-only.
arguments=$(printf '%q ' "$@")
status=0
if [ -n "${OGI_CLEAN:-}" ]; then
    ssh "$host" "rm -rf ogi/out/$name"
    rm -rf "${results:?}/$name"
fi
ssh "$host" "mkdir -p ogi/out/$name ogi/state && docker run --rm --init \
    --network none --read-only --cap-drop ALL --security-opt no-new-privileges --pids-limit 512 \
    ${OGI_CPUSET:+--cpuset-cpus $OGI_CPUSET} --memory ${OGI_MEMORY:-3g} \
    --tmpfs /tmp:rw,size=512m --tmpfs /ogi/robocode-run:rw,size=1g,uid=1000,gid=1000,mode=0755 \
    -v \$HOME/ogi/bundle:/ogi/bundle:ro -v \$HOME/ogi/rumble:/ogi/rumble:ro \
    -v \$HOME/ogi/state:/ogi/state -v \$HOME/ogi/out:/ogi/out \
    -e OGI_JVM_OPTIONS=$(printf '%q' "${OGI_JVM_OPTIONS:-}") -e OGI_BATTLE_CORE=${OGI_BATTLE_CORE:-} \
    ogi-worker:$tag $arguments" || status=$?

mkdir -p "$results/$name"
rsync -az "$host:ogi/out/$name/" "$results/$name/"
exit "$status"
