#!/usr/bin/env bash
set -e

API=24
EXPECTED_SHA256="ebb65ef6ca439333c2bb41a0c1990587288da07f6c7fd07cb3a18cc18d30ce19"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SODIUM_SRC="$ROOT_DIR/libsodium-1.0.20"
ARCHIVE="$ROOT_DIR/libsodium-1.0.20.tar.gz"
OUT_DIR="$ROOT_DIR/libsodium-android"

ANDROID_NDK="${ANDROID_NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}"

ABIS="arm64-v8a armeabi-v7a x86_64 x86"

skip=true
for ABI in $ABIS; do
    if [ ! -f "$OUT_DIR/$ABI/lib/libsodium.a" ]; then
        skip=false
        break
    fi
done
if $skip; then
    echo "libsodium уже собран для всех ABI в $OUT_DIR, пропускаю сборку"
    exit 0
fi

if [ ! -d "$ANDROID_NDK" ]; then
    echo "NDK не найден: $ANDROID_NDK" >&2
    exit 1
fi

if [ ! -d "$SODIUM_SRC" ]; then
    if [ ! -f "$ARCHIVE" ]; then
        echo "Архив не найден: $ARCHIVE" >&2
        exit 1
    fi
    actual_sha256="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
    if [ "$actual_sha256" != "$EXPECTED_SHA256" ]; then
        echo "SHA-256 архива $ARCHIVE не совпадает:" >&2
        echo "  ожидалось: $EXPECTED_SHA256" >&2
        echo "  получено:  $actual_sha256" >&2
        exit 1
    fi
    echo "Распаковываю $(basename "$ARCHIVE")"
    tar -xzf "$ARCHIVE" -C "$ROOT_DIR"
fi

TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64"
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

    (
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
    )
}

build_abi "arm64-v8a" "aarch64-linux-android" "aarch64-linux-android${API}-clang" "aarch64-linux-android${API}-clang++"
build_abi "armeabi-v7a" "armv7a-linux-androideabi" "armv7a-linux-androideabi${API}-clang" "armv7a-linux-androideabi${API}-clang++"
build_abi "x86_64" "x86_64-linux-android" "x86_64-linux-android${API}-clang" "x86_64-linux-android${API}-clang++"
build_abi "x86" "i686-linux-android" "i686-linux-android${API}-clang" "i686-linux-android${API}-clang++"

echo
echo "libsodium собран:"
for ABI in $ABIS; do
    echo "$OUT_DIR/$ABI"
done
