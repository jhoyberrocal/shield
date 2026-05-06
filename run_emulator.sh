#!/bin/bash
# Script para iniciar el emulador, instalar y lanzar la aplicación Shield

export ANDROID_HOME=$HOME/Library/Android/sdk
EMULATOR_NAME="Medium_Phone_API_36.1"

echo "📱 Iniciando emulador: $EMULATOR_NAME..."
$ANDROID_HOME/emulator/emulator -avd $EMULATOR_NAME &

echo "⏳ Esperando a que el dispositivo esté listo (esto puede tardar un poco)..."
$ANDROID_HOME/platform-tools/adb wait-for-device
sleep 5 # Esperamos unos segundos extra para asegurarnos que el sistema cargó

echo "📦 Compilando e instalando la aplicación..."
./gradlew installDebug

echo "🚀 Lanzando Shield..."
$ANDROID_HOME/platform-tools/adb shell am start -n com.jhoy.shield.debug/com.jhoy.shield.MainActivity

echo "✅ ¡Listo! La app se está ejecutando en el emulador."
