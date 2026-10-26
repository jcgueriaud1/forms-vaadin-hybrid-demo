#!/bin/bash
# Create the demo schema (db/schema.sql) in the Oracle database container.
# Re-running recreates it.
#
#   ./setup-db.sh
#   HYBRID_DB_CONTAINER=mydb HYBRID_DB_SYS_PASSWORD=secret HYBRID_DB_SERVICE=FREEPDB1 ./setup-db.sh
#
# Defaults match a gvenzl/oracle-xe container named frdb (gvenzl/oracle-free
# works the same way with HYBRID_DB_SERVICE=FREEPDB1).
set -euo pipefail
cd "$(dirname "$0")"

HYBRID_DB_CONTAINER=${HYBRID_DB_CONTAINER:-frdb}
HYBRID_DB_SYS_PASSWORD=${HYBRID_DB_SYS_PASSWORD:-Oracle12c}
HYBRID_DB_SERVICE=${HYBRID_DB_SERVICE:-XEPDB1}

docker exec -i "$HYBRID_DB_CONTAINER" sqlplus -s -L \
  "sys/$HYBRID_DB_SYS_PASSWORD@//localhost:1521/$HYBRID_DB_SERVICE" as sysdba < db/schema.sql
echo "schema ready: hybrid/hybrid@$HYBRID_DB_SERVICE"
