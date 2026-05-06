#!/bin/bash
# Script para compilar la APK de Shield

export ANDROID_HOME=$HOME/Library/Android/sdk

# Extraer automáticamente la versión del archivo build.gradle.kts
VERSION=$(grep -oE 'versionName\s*=\s*"[^"]+"' app/build.gradle.kts | cut -d'"' -f2)
if [ -z "$VERSION" ]; then
    VERSION="1.0"
fi

echo "🚀 Compilando la APK de Shield (Versión $VERSION) en modo Release para producción..."
./gradlew assembleRelease

APK_PATH="app/build/outputs/apk/release/app-release.apk"
FINAL_APK="shield-${VERSION}.apk"

if [ -f "$APK_PATH" ]; then
    # Copiar y renombrar la APK generada a la raíz del proyecto
    cp "$APK_PATH" "$FINAL_APK"
    echo "✅ ¡Compilación exitosa!"
    echo "📁 Puedes encontrar tu APK lista para instalar aquí:"
    echo "👉 $(pwd)/$FINAL_APK"
else
    echo "❌ Hubo un error al compilar la APK."
fi
