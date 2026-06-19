#!/usr/bin/env bash
set -e

ANDROID_NDK="$HOME/Android/Sdk/ndk/28.2.13676358"
API=24

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SODIUM_SRC="$ROOT_DIR/libsodium-1.0.20"
OUT_DIR="$ROOT_DIR/libsodium-android"

export TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$TOOLCHAIN/bin:$PATH"

build_abi() {
    local ABI="$1"
    local HOST="$2"
    local CC_NAME="$3"
    local CXX_NAME="$4"

    echo
    echo "========================================"
    echo "Собираю libsodium для ABI: $ABI"
    echo "HOST: $HOST"
    echo "========================================"

    cd "$SODIUM_SRC"

    make distclean >/dev/null 2>&1 || make clean >/dev/null 2>&1 || true

    export CC="${CC_NAME}"
    export CXX="${CXX_NAME}"
    export AR=llvm-ar
    export RANLIB=llvm-ranlib
    export STRIP=llvm-strip
    export CFLAGS="-fPIC"
    export CXXFLAGS="-fPIC"

    ./configure \
            --host="$HOST" \
            --prefix="$OUT_DIR/$ABI" \
            --disable-shared \
            --enable-static \
            --with-pic \
            --disable-asm

    make -j"$(nproc)"
    make install
}

build_abi "arm64-v8a" "aarch64-linux-android" "aarch64-linux-android${API}-clang" "aarch64-linux-android${API}-clang++"
build_abi "armeabi-v7a" "armv7a-linux-androideabi" "armv7a-linux-androideabi${API}-clang" "armv7a-linux-androideabi${API}-clang++"
build_abi "x86_64" "x86_64-linux-android" "x86_64-linux-android${API}-clang" "x86_64-linux-android${API}-clang++"
build_abi "x86" "i686-linux-android" "i686-linux-android${API}-clang" "i686-linux-android${API}-clang++"

echo
echo "libsodium собран:"
echo "$OUT_DIR/arm64-v8a"
echo "$OUT_DIR/armeabi-v7a"
echo "$OUT_DIR/x86_64"
echo "$OUT_DIR/x86"