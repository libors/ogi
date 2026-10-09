#!/bin/bash
# Entry point of the worker container (worker/Dockerfile): prepares a writable Robocode home and runs one harness
# main class in a JVM that sees only the minimal environment (docs/harness.md, "Foreign robots").
#
# Usage: run.sh <main class> [argument...]
#   OGI_JVM_OPTIONS  extra JVM options for this run, e.g. -Ddebug=true
#   OGI_BATTLE_CORE  if set, the JVM's application threads (the harness, the engine, the robots) run on this core
#                    only, while its own threads (JIT compilers, GC) run anywhere. In the WSL2 VM a hand-off between
#                    threads on different cores takes ~55 µs against ~4 µs on one core, and the engine hands each
#                    turn to each robot and back (docs/build-and-run.md, "Remote worker").
#
# Mounts (worker/remote.sh): /ogi/bundle (read-only), /ogi/rumble (read-only), /ogi/state, /ogi/out; a tmpfs at
# /ogi/robocode-run, which becomes the Robocode home.
set -euo pipefail

main=$1
shift
home=/ogi/robocode-run

# The installation is read-only in the image; the home links its libraries and copies its sample robots.
mkdir -p "$home/robots" "$home/config" /ogi/state/config
ln -s /opt/robocode/libs "$home/libs"
cp -r /opt/robocode/robots/. "$home/robots/"
cp /ogi/bundle/robots/*.jar "$home/robots/"
# The engine measures the CPU constant on its first start; it is kept per desktop in /ogi/state.
if [ -f /ogi/state/config/robocode.properties ]; then
    cp /ogi/state/config/robocode.properties "$home/config/"
fi

# The same JVM options as the Gradle tasks (harness/build.gradle.kts, onRobocode), for Java 17.
jvm=(
    -Xmx1g
    -Duser.home=/tmp
    -XX:+IgnoreUnrecognizedVMOptions
    --add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED
    --add-opens=java.base/java.lang.reflect=ALL-UNNAMED
    -Djava.security.manager=allow
)
# Robots may read environment variables, so the battle JVM gets only these.
environment=("PATH=$PATH")
for name in LANG LC_ALL LC_CTYPE TZ; do
    if [ -n "${!name:-}" ]; then
        environment+=("$name=${!name}")
    fi
done

# The JVM's own threads, by their native names; every other thread is an application thread.
internal='^(GC Thread#|G1 |VM Thread|Reference Handl|Finalizer|Signal Dispatch|Service Thread|Monitor Deflati|C[12] CompilerThre|Sweeper thread|Notification Th|VM Periodic Tas|Common-Cleaner)'

# Pins the application threads of process $1 to core $2 until it ends. New threads inherit the affinity of the
# thread that starts them, so after the first pass this mostly finds nothing to do.
pin_application_threads() {
    local -A seen=()
    local task tid name
    while kill -0 "$1" 2>/dev/null; do
        for task in /proc/"$1"/task/*; do
            tid=${task##*/}
            [ -n "${seen[$tid]:-}" ] && continue
            seen[$tid]=1
            name=$(cat "$task/comm" 2>/dev/null) || continue
            if ! [[ $name =~ $internal ]]; then
                taskset -p -c "$2" "$tid" >/dev/null 2>&1 || true
            fi
        done
        sleep 0.05
    done
}

cd "$home"
# shellcheck disable=SC2086  # OGI_JVM_OPTIONS holds several options
env -i "${environment[@]}" java "${jvm[@]}" ${OGI_JVM_OPTIONS:-} \
    -cp "/ogi/bundle/lib/harness.jar:/opt/robocode/libs/*" "$main" "$@" &
pid=$!
if [ -n "${OGI_BATTLE_CORE:-}" ]; then
    pin_application_threads "$pid" "$OGI_BATTLE_CORE" &
fi
status=0
wait "$pid" || status=$?

if [ ! -f /ogi/state/config/robocode.properties ] && [ -f "$home/config/robocode.properties" ]; then
    cp "$home/config/robocode.properties" /ogi/state/config/
fi
exit "$status"
