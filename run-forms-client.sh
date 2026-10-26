#!/bin/bash
# Start the demo's Forms client with the Forms Standalone Launcher (FSAL) on a
# modern JVM. macOS and Linux; on Windows use run-forms-client.cmd.
#
#   ./run-forms-client.sh [java-version]          default: 21
#   JAVA_OPTS="-Dhybrid.url=..." ./run-forms-client.sh
#
# The JVM: on macOS the installed JDK of that version, else $JAVA_HOME if
# set, else `java` on the PATH. FSAL runs the client with `java -jar frmsal.jar`, so
# we pick the JVM (OpenWebStart ignores the JNLP's Java version and stays on
# Java 8). JCEF needs the --add-opens flags below on Java 17+. The bean writes
# diagnostics to ~/hybrid-bean.log. Start hello-view first (localhost:8080).
set -euo pipefail

JAVA_VERSION=${1:-21}
HYBRID_FORMS_URL=${HYBRID_FORMS_URL:-http://localhost:7777/forms}
DIR="${TMPDIR:-/tmp}/fsal"
ADD_OPENS="--add-opens java.desktop/sun.awt=ALL-UNNAMED"
if [ "$(uname -s)" = Darwin ]; then
  ADD_OPENS="$ADD_OPENS --add-opens java.desktop/sun.lwawt=ALL-UNNAMED
    --add-opens java.desktop/sun.lwawt.macosx=ALL-UNNAMED"
fi

if [ -x /usr/libexec/java_home ]; then
  JAVA=$(/usr/libexec/java_home -v "$JAVA_VERSION")/bin/java
elif [ -n "${JAVA_HOME:-}" ]; then
  JAVA=$JAVA_HOME/bin/java
else
  JAVA=java
fi
"$JAVA" -version 2>&1 | head -1

mkdir -p "$DIR"
# Always fetch frmsal.jar from the server, so it matches its Forms version.
curl -sf -o "$DIR/frmsal.jar" "$HYBRID_FORMS_URL/java/frmsal.jar"
# shellcheck disable=SC2086  # both are lists of options
exec "$JAVA" $ADD_OPENS ${JAVA_OPTS:-} -jar "$DIR/frmsal.jar" \
  -url "$HYBRID_FORMS_URL/frmservlet?config=hybrid" -t 10000
