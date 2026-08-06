#!/usr/bin/env bash
# OIE Sentinel — channel monitoring & alerting plugin.
# Published under the terms of the Mozilla Public License 2.0.
#
# Release build: compiles shared/server, builds the webadmin dashboard bundle
# (frontend-maven-plugin runs npm inside webadmin/), and packs everything into
# the installable extension zip.
#
#   ./build.sh            plain build
#   ./build.sh -Psigning  build with jar signing (see root pom.xml for the
#                         YubiKey/PKCS11 properties it expects)
#
# Prerequisites:
#   - JDK 21+ on PATH (the 4.6.0 engine jars are Java 21 bytecode; the plugin
#     itself still targets --release 17)
#   - engine jars installed into the local Maven repository:
#       ENGINE_DIR=/path/to/engine ./scripts/install-engine-jars.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if ! command -v mvn >/dev/null 2>&1; then
    echo "error: mvn not found on PATH" >&2
    exit 1
fi

JAVA_MAJOR="$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1)"
if [[ -n "${JAVA_MAJOR}" && "${JAVA_MAJOR}" -lt 21 ]]; then
    echo "error: JDK ${JAVA_MAJOR} found, but the engine jars need JDK 21+ to read" >&2
    exit 1
fi

if ! mvn -q dependency:get -Dartifact=com.mirth.connect:mirth-server:4.6.0 -o >/dev/null 2>&1; then
    echo "error: engine jars missing from the local Maven repository." >&2
    echo "  run: ENGINE_DIR=/path/to/engine ./scripts/install-engine-jars.sh" >&2
    exit 1
fi

mvn clean package "$@"

ZIP="$(ls package/target/sentinel-*.zip 2>/dev/null | head -1 || true)"
if [[ -z "${ZIP}" ]]; then
    echo "error: build finished but no package/target/sentinel-*.zip was produced" >&2
    exit 1
fi

# ---- library drift check -------------------------------------------------
#
# package/resources/plugin.xml hand-lists every jar the extension classloader
# should load, while Maven decides what actually gets staged. Nothing keeps
# the two in step, and each way they can diverge fails quietly:
#
#   declared but absent  -> the engine logs "could not locate library" at ERROR
#                           and carries on with a short classpath; the plugin
#                           half-works until something hits the missing class.
#   present but undeclared -> the jar ships dead weight the classloader never
#                           sees, so the failure looks like a missing dependency
#                           even though the zip visibly contains it.
#
# Both are build-time-detectable, so detect them at build time. Compared inside
# the zip, where ${project.version} is already resolved.
DECLARED="$(unzip -p "${ZIP}" 'sentinel/plugin.xml' 2>/dev/null \
    | sed -n 's/.*<library[^>]*path="\([^"]*\)".*/\1/p' | sort -u)"
STAGED="$(unzip -Z1 "${ZIP}" 'sentinel/*.jar' 2>/dev/null | xargs -n1 basename | sort -u)"

MISSING="$(comm -23 <(printf '%s\n' "${DECLARED}") <(printf '%s\n' "${STAGED}"))"
UNDECLARED="$(comm -13 <(printf '%s\n' "${DECLARED}") <(printf '%s\n' "${STAGED}"))"

if [[ -n "${MISSING}" || -n "${UNDECLARED}" ]]; then
    echo >&2
    echo "error: plugin.xml and the packaged jars disagree." >&2
    [[ -n "${MISSING}" ]] && {
        echo "  declared in plugin.xml but NOT in the zip (engine logs 'could not locate library'):" >&2
        printf '    %s\n' ${MISSING} >&2
    }
    [[ -n "${UNDECLARED}" ]] && {
        echo "  in the zip but NOT declared in plugin.xml (never reaches the classloader):" >&2
        printf '    %s\n' ${UNDECLARED} >&2
    }
    echo >&2
    echo "  fix package/resources/plugin.xml to match:" >&2
    echo "    ls package/target/to-be-packed/*.jar | xargs -n1 basename" >&2
    exit 1
fi

echo
echo "extension archive: ${ZIP}"
echo "library manifest: $(printf '%s\n' "${DECLARED}" | wc -l | tr -d ' ') declared, all present"
unzip -l "${ZIP}" 2>/dev/null || jar tf "${ZIP}" | sed 's/^/  /'
