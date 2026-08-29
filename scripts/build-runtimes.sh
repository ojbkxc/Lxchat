#!/bin/bash
# Batch build v4 — dynamic link + glibc linker wrapper for Android
# Python: make only, stage binary + Lib/ + wrapper using glibc ld-linux
# Node.js: make only, stage binary + wrapper using glibc ld-linux
# FFmpeg: apt install, copy system binary

LOG="/root/python-build/batch-build.log"
PKAGES="/root/python-build/packages"
mkdir -p "$PKAGES"

log() { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$LOG"; }

bundle_libs() {
    local binary="$1" pkg_dir="$2"
    mkdir -p "$pkg_dir/lib"
    local deps=$(ldd "$binary" 2>/dev/null | grep '=>' | awk '{print $3}' | grep -v '^$')
    for d in $deps; do [ -f "$d" ] && cp -n "$d" "$pkg_dir/lib/" 2>/dev/null; done
    # Copy dynamic linker
    local lk=$(readelf -l "$binary" 2>/dev/null | grep 'interpreter' | sed 's/.*: //;s/\]//')
    [ -n "$lk" ] && [ -f "$lk" ] && cp "$lk" "$pkg_dir/lib/" 2>/dev/null
    ls "$pkg_dir/lib/" 2>/dev/null | tr '\n' ' '
}

create_wrapper() {
    local pkg_dir="$1" bin_name="$2" extra_env="$3"
    mv "$pkg_dir/bin/$bin_name" "$pkg_dir/bin/${bin_name}.real"
    # Wrapper uses bundled glibc ld-linux to load binary, bypassing Android linker64
    cat > "$pkg_dir/bin/$bin_name" << EOF
#!/bin/sh
DIR="\$(cd "\$(dirname "\$0")" && pwd)"
ROOT="\$(cd "\$DIR/.." && pwd)"
$extra_env
exec "\$ROOT/lib/ld-linux-aarch64.so.1" --library-path "\$ROOT/lib" "\$DIR/${bin_name}.real" "\$@"
EOF
    chmod +x "$pkg_dir/bin/$bin_name"
}

make_zip() {
    local pkg_dir="$1" name="$2" zip_name="$3"
    echo "$4" > "$pkg_dir/manifest.json"
    cd /tmp && rm -f "$PKAGES/$zip_name"
    zip -r -q "$PKAGES/$zip_name" "$name/"
    cd - && rm -rf "$pkg_dir"
    log "Package: $zip_name ($(du -sh "$PKAGES/$zip_name" | cut -f1))"
}

build_python() {
    local ver="$1" short="$2"
    local src="/root/python-build/Python-$ver"
    local url="https://www.python.org/ftp/python/$ver/Python-$ver.tgz"
    log "Building Python $ver..."
    if [ ! -f "$src/python" ]; then
        cd /root/python-build
        [ ! -f "Python-$ver.tgz" ] && wget -q "$url"
        tar xzf "Python-$ver.tgz"
        cd "$src"
        ./configure --without-ensurepip --disable-shared > /root/python-build/cfg-py-$short.log 2>&1
        make -j2 > /root/python-build/bld-py-$short.log 2>&1 || { log "Python $ver make FAILED"; return 1; }
    fi
    local pkg="/tmp/pkg-py-$short"
    rm -rf "$pkg" && mkdir -p "$pkg/bin" "$pkg/lib/python$short"
    cp "$src/python" "$pkg/bin/python$short"
    cp -r "$src/Lib/"* "$pkg/lib/python$short/"
    if [ -d "$src/build/lib.linux-aarch64-$short" ]; then
        mkdir -p "$pkg/lib/python$short/lib-dynload"
        cp "$src/build/lib.linux-aarch64-$short"/*.so "$pkg/lib/python$short/lib-dynload/" 2>/dev/null || true
    fi
    find "$pkg/lib" -name '*.pyc' -delete 2>/dev/null
    find "$pkg/lib" -type d -name '__pycache__' -exec rm -rf {} + 2>/dev/null
    find "$pkg/lib" -type d -name 'test*' -exec rm -rf {} + 2>/dev/null
    find "$pkg/lib" -type d -name 'tests' -exec rm -rf {} + 2>/dev/null
    find "$pkg/lib" -type d -name 'idlelib' -exec rm -rf {} + 2>/dev/null
    find "$pkg/lib" -type d -name 'tkinter' -exec rm -rf {} + 2>/dev/null
    local libs=$(bundle_libs "$pkg/bin/python$short" "$pkg")
    create_wrapper "$pkg" "python$short" "export PYTHONHOME=\"\$ROOT\""
    log "Python $ver staged, deps: $libs"
    local manifest="{\"id\":\"runtime-python\",\"type\":\"python\",\"version\":\"$ver\",\"entry\":\"bin/python$short\",\"startCommand\":[\"{root}/bin/python$short\"],\"binarySource\":\"CPython $ver\",\"license\":\"PSF-2.0\",\"sourceUrl\":\"https://www.python.org/ftp/python/$ver/\",\"versions\":[\"$ver\"],\"minVersion\":\"$ver\"}"
    make_zip "$pkg" "pkg-py-$short" "python-$ver-android-arm64.zip" "$manifest"
}

build_node() {
    local ver="$1" short="$2"
    local src="/root/python-build/node-v$ver"
    local url="https://nodejs.org/dist/v$ver/node-v$ver.tar.gz"
    log "Building Node.js v$ver..."
    if [ ! -f "$src/node" ]; then
        cd /root/python-build
        [ ! -f "node-v$ver.tar.gz" ] && wget -q "$url"
        tar xzf "node-v$ver.tar.gz"
        cd "$src"
        ./configure --without-npm > /root/python-build/cfg-node-$short.log 2>&1
        make -j2 > /root/python-build/bld-node-$short.log 2>&1 || { log "Node v$ver make FAILED"; return 1; }
    fi
    local pkg="/tmp/pkg-node-$short"
    rm -rf "$pkg" && mkdir -p "$pkg/bin"
    cp "$src/node" "$pkg/bin/node"
    local libs=$(bundle_libs "$pkg/bin/node" "$pkg")
    create_wrapper "$pkg" "node" ""
    log "Node.js v$ver staged, deps: $libs"
    local manifest="{\"id\":\"runtime-node\",\"type\":\"node\",\"version\":\"$ver\",\"entry\":\"bin/node\",\"startCommand\":[\"{root}/bin/node\"],\"binarySource\":\"Node.js v$ver\",\"license\":\"MIT\",\"sourceUrl\":\"https://nodejs.org/dist/v$ver/\",\"versions\":[\"$ver\"],\"minVersion\":\"$ver\"}"
    make_zip "$pkg" "pkg-node-$short" "node-$ver-android-arm64.zip" "$manifest"
}

build_ffmpeg_apt() {
    log "Installing FFmpeg via apt..."
    apt-get install -y -qq ffmpeg 2>/dev/null || true
    local bin="/usr/bin/ffmpeg"
    if [ ! -f "$bin" ]; then log "FFmpeg not found"; return 1; fi
    # Extract clean version: "ffmpeg version 5.1.9-0+deb12u1 ..." → "5.1.9"
    local actual_ver=$($bin -version 2>/dev/null | head -1 | awk '{print $3}' | sed 's/-.*//')
    log "System FFmpeg version: $actual_ver"
    local pkg="/tmp/pkg-ffmpeg"
    rm -rf "$pkg" && mkdir -p "$pkg/bin"
    cp "$bin" "$pkg/bin/ffmpeg"
    local libs=$(bundle_libs "$pkg/bin/ffmpeg" "$pkg")
    create_wrapper "$pkg" "ffmpeg" ""
    log "FFmpeg staged, deps: $libs"
    local manifest="{\"id\":\"runtime-ffmpeg\",\"type\":\"ffmpeg\",\"version\":\"$actual_ver\",\"entry\":\"bin/ffmpeg\",\"startCommand\":[\"{root}/bin/ffmpeg\"],\"binarySource\":\"System ffmpeg $actual_ver\",\"license\":\"LGPL-3.0-or-later\",\"sourceUrl\":\"https://ffmpeg.org/\",\"versions\":[\"$actual_ver\"],\"minVersion\":\"$actual_ver\"}"
    make_zip "$pkg" "pkg-ffmpeg" "ffmpeg-$actual_ver-android-arm64.zip" "$manifest"
}

log "=== Batch build v4 (glibc linker wrapper) ==="
log "Installing deps..."
apt-get update -qq 2>/dev/null
apt-get install -y -qq xz-utils nasm yasm pkg-config ffmpeg patchelf zip 2>/dev/null || true

# Clean old packages
rm -f "$PKAGES"/*.zip

build_python "3.11.9" "3.11"
build_python "3.12.7" "3.12"
build_python "3.13.2" "3.13"
build_python "3.14.0" "3.14"
build_node "24.0.0" "24"
build_ffmpeg_apt

log "=== Complete ==="
ls -lh "$PKAGES/" 2>/dev/null | tee -a "$LOG"
log "=== DONE ==="
