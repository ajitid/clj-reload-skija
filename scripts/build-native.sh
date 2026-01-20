#!/bin/bash
#
# Build the macOS native library and Java JNI wrapper.
#
# This compiles:
# 1. macos_main.m -> libmacos_main.dylib (native library)
# 2. MainThread.java -> MainThread.class (JNI wrapper)
#
# Usage: ./scripts/build-native.sh
#
# Requirements:
#   - Xcode Command Line Tools (clang)
#   - JAVA_HOME environment variable set (or auto-detected)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
NATIVE_DIR="$PROJECT_ROOT/native"
JAVA_SRC_DIR="$PROJECT_ROOT/src/java"
CLASSES_DIR="$PROJECT_ROOT/classes"

# Auto-detect JAVA_HOME on macOS if not set
if [ -z "$JAVA_HOME" ]; then
    if [ -x "/usr/libexec/java_home" ]; then
        JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true)
    fi
fi

if [ -z "$JAVA_HOME" ]; then
    echo "Error: JAVA_HOME not set and could not auto-detect Java installation."
    echo "Please set JAVA_HOME to your JDK installation directory."
    exit 1
fi

echo "Using JAVA_HOME: $JAVA_HOME"

# Check for JNI headers
JNI_INCLUDE="$JAVA_HOME/include"
JNI_INCLUDE_DARWIN="$JAVA_HOME/include/darwin"

if [ ! -d "$JNI_INCLUDE" ]; then
    echo "Error: JNI include directory not found at $JNI_INCLUDE"
    exit 1
fi

if [ ! -d "$JNI_INCLUDE_DARWIN" ]; then
    echo "Error: Darwin JNI include directory not found at $JNI_INCLUDE_DARWIN"
    exit 1
fi

# Determine architecture
ARCH=$(uname -m)
if [ "$ARCH" = "arm64" ]; then
    ARCH_FLAG="-arch arm64"
else
    ARCH_FLAG="-arch x86_64"
fi

echo "Building for architecture: $ARCH"

# Compile
echo "Compiling native/macos_main.m..."
clang -shared -fPIC $ARCH_FLAG \
    -o "$NATIVE_DIR/libmacos_main.dylib" \
    -framework Foundation \
    -framework CoreFoundation \
    -I"$JNI_INCLUDE" \
    -I"$JNI_INCLUDE_DARWIN" \
    "$NATIVE_DIR/macos_main.m"

echo "Successfully built: $NATIVE_DIR/libmacos_main.dylib"

# Verify the library
echo ""
echo "Library info:"
file "$NATIVE_DIR/libmacos_main.dylib"
echo ""
echo "Exported symbols:"
nm -g "$NATIVE_DIR/libmacos_main.dylib" | grep "Java_lib_window"

# ============================================================
# Step 2: Compile Java JNI wrapper
# ============================================================

echo ""
echo "=== Compiling Java JNI wrapper ==="

# Create classes directory
mkdir -p "$CLASSES_DIR"

# Compile Java file
echo "Compiling MainThread.java..."
javac -d "$CLASSES_DIR" "$JAVA_SRC_DIR/lib/window/macos/MainThread.java"

echo "Successfully compiled to: $CLASSES_DIR/lib/window/macos/MainThread.class"

echo ""
echo "=== Build complete ==="
echo "Native library: $NATIVE_DIR/libmacos_main.dylib"
echo "Java classes: $CLASSES_DIR/"
