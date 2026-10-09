#!/bin/sh
# Compila el MIDlet KinIRC en Linux y genera KinIRC.jar + app.jad
# Equivalente Linux de build.ps1 (que es solo para Windows).
#
# Requisitos:
#   - JDK 8 (javac con -source 1.3). Ej: Temurin 8.
#   - ProGuard (solo se usa como preverificador: -dontshrink -dontoptimize -dontobfuscate -microedition)
#   - cldcapi11.jar y midpapi20.jar (APIs de Sun WTK; valen los stubs de MicroEmulator en Maven Central:
#       org/microemu/cldcapi11 y org/microemu/midpapi20)
#
# Variables de entorno (todas opcionales):
#   JDK8_HOME     Directorio del JDK 8. Por defecto se busca en /tmp/open/sdk/jdk8u*,
#                 /usr/lib/jvm/*8* y JAVA_HOME (si es 1.8).
#   PROGUARD_JAR  Ruta a proguard.jar. Por defecto /usr/share/java/proguard.jar (paquete Debian proguard-cli).
#   CLDC_API      Ruta a cldcapi11.jar. Por defecto /tmp/open/sdk/lib/cldcapi11.jar
#   MIDP_API      Ruta a midpapi20.jar. Por defecto /tmp/open/sdk/lib/midpapi20.jar
#
# Uso: ./build.sh
set -e
ROOT=$(cd "$(dirname "$0")" && pwd)

find_jdk8() {
    if [ -n "$JDK8_HOME" ] && [ -x "$JDK8_HOME/bin/javac" ]; then
        echo "$JDK8_HOME"
        return 0
    fi
    for d in /tmp/open/sdk/jdk8u* /usr/lib/jvm/*8* /usr/lib/jvm/*-8-*; do
        if [ -x "$d/bin/javac" ]; then
            echo "$d"
            return 0
        fi
    done
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        if "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "1\.8'; then
            echo "$JAVA_HOME"
            return 0
        fi
    fi
    return 1
}

JDK8=$(find_jdk8) || {
    echo "ERROR: sin JDK 8 no se puede compilar: javac moderno genera clases v52+" >&2
    echo "que el KVM (W200i, KEmulator) rechaza (Invalid class version: 52)." >&2
    echo "Instala Temurin 8 y define JDK8_HOME, o reutiliza un JAR con clases v45" >&2
    echo "inyectando solo recursos (ver fix anterior con unzip/jar cfm)." >&2
    exit 1
}
JAVAC="$JDK8/bin/javac"
JARTOOL="$JDK8/bin/jar"
JAVATOOL="$JDK8/bin/java"
USE_JDK8=1
CLDC_API=${CLDC_API:-/tmp/open/sdk/lib/cldcapi11.jar}
MIDP_API=${MIDP_API:-/tmp/open/sdk/lib/midpapi20.jar}
PROGUARD_JAR=${PROGUARD_JAR:-/usr/share/java/proguard.jar}

for f in "$CLDC_API" "$MIDP_API"; do
    if [ ! -f "$f" ]; then
        echo "No se encontro $f. Define CLDC_API / MIDP_API." >&2
        exit 1
    fi
done
if [ ! -f "$PROGUARD_JAR" ]; then
    echo "No se encontro $PROGUARD_JAR. Instala proguard-cli o define PROGUARD_JAR." >&2
    exit 1
fi

BUILD="$ROOT/build"
CLASSES="$BUILD/classes"
VERIFIED="$BUILD/verified"
INJAR="$BUILD/in.jar"
OUTJAR="$BUILD/verified.jar"
PROCONF="$BUILD/midlets.pro"
JARPATH="$ROOT/KinIRC.jar"
JADPATH="$ROOT/app.jad"
MANIFEST="$ROOT/manifest.mf"

rm -rf "$CLASSES" "$VERIFIED" "$INJAR" "$OUTJAR"
mkdir -p "$CLASSES" "$VERIFIED"

# 1. Compilar contra las APIs J2ME con JDK 8 (-source 1.3 -> clases v47/45
# aptas para KVM). NO usar javac moderno: genera v52+ y el movil dice
# "error de aplicacion" y el emulador "Invalid class version: 52".
# shellcheck disable=SC2046
"$JAVAC" -source 1.3 -target 1.1 -encoding UTF-8 \
    -bootclasspath "$CLDC_API:$MIDP_API" \
    -d "$CLASSES" $(find "$ROOT/src" -name '*.java')

# 2. Preverificar con ProGuard (genera atributos StackMap exigidos por el KVM del movil).
cat > "$PROCONF" <<EOF
-injars $INJAR
-outjars $OUTJAR
-libraryjars $CLDC_API
-libraryjars $MIDP_API
-dontshrink
-dontoptimize
-dontobfuscate
-microedition
-dontwarn
-dontnote
EOF
"$JARTOOL" cf "$INJAR" -C "$CLASSES" .
"$JAVATOOL" -jar "$PROGUARD_JAR" @"$PROCONF"

# 3. Empaquetar JAR final con el manifest del proyecto.
rm -rf "$VERIFIED"
mkdir -p "$VERIFIED"
unzip -q -o "$OUTJAR" -d "$VERIFIED"
rm -rf "$VERIFIED/META-INF"
if [ -d "$ROOT/res" ]; then
    cp -r "$ROOT/res/." "$VERIFIED/" 2>/dev/null || true
fi
rm -f "$JARPATH"
"$JARTOOL" cfm "$JARPATH" "$MANIFEST" -C "$VERIFIED" .

# 4. Actualizar el tamano en el JAD.
SIZE=$(wc -c < "$JARPATH" | tr -d ' ')
TMPJAD="$JADPATH.tmp"
grep -v '^MIDlet-Jar-Size:' "$JADPATH" > "$TMPJAD" || true
printf 'MIDlet-Jar-Size: %s\n' "$SIZE" >> "$TMPJAD"
mv "$TMPJAD" "$JADPATH"

echo "JAR creado: $JARPATH ($SIZE bytes)"
echo "JAD creado: $JADPATH"
