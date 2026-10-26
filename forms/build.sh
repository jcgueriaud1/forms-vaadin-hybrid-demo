#!/bin/bash
# Runs INSIDE the Forms 12.2.1.4 container (deploy-form.sh copies it there).
# Builds hybrid_demo.fmx from hybrid_demo_fmb.xml and registers the [hybrid]
# config section. Idempotent.
#
# Two traps this avoids:
#  * frmxml2f (JDAPI) needs an X display and FORMS_API_TK_BYPASS=TRUE, or it
#    dies with "_jni_initialize: Failed to create new forms context".
#  * The compiler must run through the instance wrapper frmcmp_batch.sh (it sets
#    the JVM library path, else FRM-91500), and silently rejects easy-connect
#    strings, so it connects through a tnsnames alias.
set -euo pipefail

: "${HYBRID_FORMS_DOMAIN:=/opt/oracle/user_projects/domains/FRDEMO}"
: "${HYBRID_DB_HOST:=frdb}"
: "${HYBRID_DB_SERVICE:=XEPDB1}"
export ORACLE_HOME=${ORACLE_HOME:-/opt/oracle}
FMW=$HYBRID_FORMS_DOMAIN/config/fmwconfig
FORMS_INSTANCE=$FMW/components/FORMS/instances/forms1
APP=$FMW/servers/WLS_FORMS/applications/formsapp_12.2.1/config
SRC=/opt/oracle/user_projects/hybrid
ALIAS=HYBRIDDB
CONN="hybrid/hybrid@$ALIAS"

cd "$SRC"
TNS_ENTRY="$ALIAS = (DESCRIPTION = (ADDRESS = (PROTOCOL = TCP)(HOST = $HYBRID_DB_HOST)(PORT = 1521))
  (CONNECT_DATA = (SERVICE_NAME = $HYBRID_DB_SERVICE)))"

# 1) XML -> FMB
export FORMS_API_TK_BYPASS=TRUE DISPLAY=:98
Xvfb :98 -screen 0 1024x768x24 >/tmp/xvfb-hybrid.log 2>&1 &
XVFB_PID=$!
trap 'kill $XVFB_PID 2>/dev/null || true' EXIT
sleep 2
rm -f hybrid_demo.fmb
sh "$FORMS_INSTANCE/bin/frmxml2f.sh" hybrid_demo_fmb.xml OVERWRITE=YES
mv -f hybrid_demo_fmb.fmb hybrid_demo.fmb 2>/dev/null || true
[ -f hybrid_demo.fmb ] || { echo "ERROR: frmxml2f produced no fmb" >&2; exit 1; }

# 2) FMB -> FMX (compile-time alias in a private TNS_ADMIN)
export TNS_ADMIN=/tmp/hybridtns NLS_LANG=AMERICAN_AMERICA.AL32UTF8 FORMS_PATH=$SRC
mkdir -p "$TNS_ADMIN"
echo "$TNS_ENTRY" > "$TNS_ADMIN/tnsnames.ora"
rm -f hybrid_demo.fmx hybrid_demo.err
sh "$FORMS_INSTANCE/bin/frmcmp_batch.sh" module="$SRC/hybrid_demo.fmb" userid="$CONN" \
  module_type=FORM batch=yes compile_all=yes || true
[ -f hybrid_demo.fmx ] || { cat hybrid_demo.err 2>/dev/null; echo "ERROR: compile failed" >&2; exit 1; }
echo "built: $SRC/hybrid_demo.fmx"

# 3) Runtime: the same alias for the Forms server (runtime TNS_ADMIN = $FMW)
grep -q "^$ALIAS" "$FMW/tnsnames.ora" 2>/dev/null || printf '\n%s\n' "$TNS_ENTRY" >> "$FMW/tnsnames.ora"

# 4) hybrid.env = default.env with this directory on FORMS_PATH, and a UTF-8
#    client character set (without NLS_LANG the runtime uses US7ASCII and
#    "Łódź" shows as "Lodz")
sed "s#^FORMS_PATH=.*#&:$SRC#" "$APP/default.env" > "$APP/hybrid.env"
echo "NLS_LANG=AMERICAN_AMERICA.AL32UTF8" >> "$APP/hybrid.env"

# 5) [hybrid] config section, rewritten on every run so it matches this
#    script. baseSAAfile is required by the standalone launcher (FRM-93136
#    without it); archive= lists the bean jar; width/height size the window.
awk '/^\[hybrid\]/{skip=1; next} /^\[/{skip=0} !skip' "$APP/formsweb.cfg" > "$APP/formsweb.cfg.tmp"
cat >> "$APP/formsweb.cfg.tmp" <<EOF
[hybrid]
form=hybrid_demo.fmx
userid=$CONN
envFile=hybrid.env
webstart=enabled
basejnlp=base.jnlp
baseSAAfile=basesaa.txt
archive=frmall.jar,hybrid-bean.jar
lookAndFeel=Oracle
separateFrame=true
pageTitle=Forms and Vaadin hybrid demo
width=1010
height=930
EOF
mv "$APP/formsweb.cfg.tmp" "$APP/formsweb.cfg"
echo "cfg: wrote [hybrid] in formsweb.cfg"
