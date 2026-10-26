#!/bin/bash
# Build, self-sign and deploy hybrid-bean.jar to the Forms codebase in the Forms
# container. The [hybrid] config section (written by deploy-form.sh) lists it
# in archive=. Idempotent.
#
#   ./deploy-bean.sh
#
# frmall.jar (the Forms client API the bean compiles against) is Oracle's: it is
# downloaded from your Forms server into forms-bean/lib/ and never committed or
# bundled. The jar is self-signed for the demo; the keystore lives outside the
# repo, in ~/.forms-vaadin-hybrid/.
set -euo pipefail
cd "$(dirname "$0")"

HYBRID_FORMS_CONTAINER=${HYBRID_FORMS_CONTAINER:-frfmw}
HYBRID_FORMS_URL=${HYBRID_FORMS_URL:-http://localhost:7777/forms}
FORMS_JAVA=/opt/oracle/forms/java
KEYSTORE=$HOME/.forms-vaadin-hybrid/selfsigned.p12
STOREPASS=changeit
ALIAS=forms-vaadin-hybrid

[ -f forms-bean/lib/frmall.jar ] || curl -sf -o forms-bean/lib/frmall.jar --create-dirs \
  "$HYBRID_FORMS_URL/java/frmall.jar"
mvn -q -B install -DskipTests -pl forms-bean -am

if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -storepass "$STOREPASS" \
    -alias "$ALIAS" -keyalg RSA -keysize 3072 -validity 3650 \
    -dname "CN=Forms-Vaadin hybrid demo (self-signed)"
fi
jarsigner -keystore "$KEYSTORE" -storepass "$STOREPASS" \
  -signedjar forms-bean/target/hybrid-bean-signed.jar forms-bean/target/hybrid-bean.jar "$ALIAS"

docker cp forms-bean/target/hybrid-bean-signed.jar "$HYBRID_FORMS_CONTAINER:$FORMS_JAVA/hybrid-bean.jar"
docker exec -u root "$HYBRID_FORMS_CONTAINER" chown oracle:oracle "$FORMS_JAVA/hybrid-bean.jar"
echo "deployed: $HYBRID_FORMS_URL/java/hybrid-bean.jar"
