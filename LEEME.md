# Nix Ai (Android)

App Android que envuelve la interfaz web de Nix Ai (`app/src/main/assets/index.html`)
y se conecta a tu propio modelo open source (Ollama, LM Studio, llama.cpp, vLLM...).

## Compilar el APK (elige una opción)

### Opción A: GitHub (sin instalar nada)
1. Crea un repositorio nuevo en github.com y sube todo el contenido de esta carpeta.
2. Ve a la pestaña **Actions** → **Compilar Nix Ai (APK)** → **Run workflow**.
3. Cuando termine (3-5 min), descarga el artefacto **NixAi-apk** y descomprime `app-debug.apk`.

### Opción B: Android Studio
1. Abre esta carpeta con Android Studio y espera la sincronización de Gradle.
2. Menú **Build → Build APK(s)**. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

### Opción C: Terminal (con JDK 17 y Android SDK instalados)
```
./gradlew assembleDebug
```

## Instalar en el celular
Pasa `app-debug.apk` al teléfono y ábrelo (permite "instalar apps de orígenes desconocidos" si te lo pide).

## Conectar con tu modelo
En tu PC:
```
ollama pull qwen2.5-coder:7b
# Windows (PowerShell):  setx OLLAMA_HOST 0.0.0.0 ; setx OLLAMA_ORIGINS *   (reinicia Ollama)
# Linux/Mac:             OLLAMA_HOST=0.0.0.0 OLLAMA_ORIGINS=* ollama serve
```
En la app: ☰ → ⚙ Configuración → URL del servidor: `http://IP-DE-TU-PC:11434`
(el celular y la PC deben estar en la misma red Wi-Fi; revisa que el firewall permita el puerto 11434).

Nota: la interfaz carga marked, DOMPurify y highlight.js desde cdnjs; sin internet el chat funciona,
pero sin formato Markdown ni colores en el código.
