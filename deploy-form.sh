#!/bin/bash
# Build the forms (forms/*_fmb.xml) in the Forms container and register the
# [hybrid] and [hybrid-notes] config sections. Idempotent.
#
#   ./deploy-form.sh
#   HYBRID_FORMS_CONTAINER=myforms HYBRID_DB_HOST=mydb HYBRID_DB_SERVICE=FREEPDB1 ./deploy-form.sh
#
# The XML is the source of truth (Forms Builder is only for viewing). Run
# setup-db.sh first: the compiler checks the form against the CUSTOMERS table.
set -euo pipefail
cd "$(dirname "$0")"

HYBRID_FORMS_CONTAINER=${HYBRID_FORMS_CONTAINER:-frfmw}
SRC=/opt/oracle/user_projects/hybrid

docker exec "$HYBRID_FORMS_CONTAINER" mkdir -p "$SRC"
for xml in forms/*_fmb.xml; do
  docker cp "$xml" "$HYBRID_FORMS_CONTAINER:$SRC/"
done
docker cp forms/build.sh "$HYBRID_FORMS_CONTAINER:$SRC/"
docker exec -u root "$HYBRID_FORMS_CONTAINER" chown -R oracle:oracle "$SRC"
docker exec -e HYBRID_DB_HOST="${HYBRID_DB_HOST:-frdb}" -e HYBRID_DB_SERVICE="${HYBRID_DB_SERVICE:-XEPDB1}" \
  "$HYBRID_FORMS_CONTAINER" bash "$SRC/build.sh"
