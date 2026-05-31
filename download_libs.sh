#!/bin/bash

echo "📥 Baixando bibliotecas nativas libtorrent..."

# Cria diretórios
mkdir -p app/src/main/jniLibs/arm64-v8a
mkdir -p app/src/main/jniLibs/armeabi-v7a
mkdir -p app/src/main/jniLibs/x86

# Baixa do repositório do LibreTorrent (APK compilado)
echo "Baixando LibreTorrent APK para extrair bibliotecas..."

# Download do APK do LibreTorrent (versão 3.6)
wget -q -O /tmp/libretorrent.apk \
  "https://github.com/proninyaroslav/libretorrent/releases/download/v3.6/libretorrent-v3.6.apk" 2>/dev/null

if [ -f /tmp/libretorrent.apk ]; then
    echo "Extraindo bibliotecas nativas..."
    
    # Extrai as .so do APK
    unzip -q -o /tmp/libretorrent.apk "lib/*/libtorrent4j.so" -d /tmp/libextract/
    
    # Copia para as pastas corretas
    cp /tmp/libextract/lib/arm64-v8a/libtorrent4j.so app/src/main/jniLibs/arm64-v8a/libtorrent.so 2>/dev/null
    cp /tmp/libextract/lib/armeabi-v7a/libtorrent4j.so app/src/main/jniLibs/armeabi-v7a/libtorrent.so 2>/dev/null
    cp /tmp/libextract/lib/x86/libtorrent4j.so app/src/main/jniLibs/x86/libtorrent.so 2>/dev/null
    
    echo "✅ Bibliotecas extraídas com sucesso!"
    echo "   arm64-v8a: $(ls -la app/src/main/jniLibs/arm64-v8a/ 2>/dev/null | grep libtorrent)"
    echo "   armeabi-v7a: $(ls -la app/src/main/jniLibs/armeabi-v7a/ 2>/dev/null | grep libtorrent)"
    echo "   x86: $(ls -la app/src/main/jniLibs/x86/ 2>/dev/null | grep libtorrent)"
else
    echo "❌ Não foi possível baixar o APK."
    echo "   As bibliotecas .so precisam ser adicionadas manualmente."
fi

# Limpeza
rm -rf /tmp/libretorrent.apk /tmp/libextract

echo ""
echo "⚠️  Se o download falhar, baixe manualmente as libs de:"
echo "   https://github.com/proninyaroslav/libretorrent/releases"
