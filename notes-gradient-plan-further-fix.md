# Plan: Fix SIGSEGV in Gradient CS JNI Functions

## Root Cause

The crash (`SIGSEGV in jni_GetFloatArrayElements`) is caused by **null `posArray` handling missing in all 4 CS variants** of `Shader.cc`.

### The bug

The Clojure code passes `nil` for positions (meaning "evenly spaced"):
```clojure
;; gradient_spaces.clj line 47:
(grad/linear-gradient x y (+ x w) y colors nil :clamp interp-cs hue-method)
;;                                        ^^^ nil positions
```

This `nil` flows through to Java as `null`, then into JNI as a null `jfloatArray`. The CS variants don't check for null:

```cpp
// Shader.cc line 71 — CS variant (BUGGY, no null check):
float* pos = env->GetFloatArrayElements(posArray, nullptr);  // CRASH if posArray is null

// Shader.cc line 80 — also buggy:
env->GetArrayLength(posArray)  // CRASH if posArray is null

// Shader.cc line 82 — also buggy:
env->ReleaseFloatArrayElements(posArray, pos, 0);  // CRASH if posArray is null
```

Compare with the non-CS variants which handle null correctly:
```cpp
// Shader.cc line 56 — non-CS variant (CORRECT):
float* pos = posArray == nullptr ? nullptr : env->GetFloatArrayElements(posArray, nullptr);
// line 59: uses colorsArray for count, not posArray
// line 61-62: if (posArray != nullptr) guard on Release
```

This is a pre-existing bug in the original Skija code, never triggered before because the CS code path wasn't used with null positions.

### Three issues per CS function (×4 functions = 12 fixes)

1. `GetFloatArrayElements(posArray, ...)` — needs `posArray == nullptr ?` guard
2. `GetArrayLength(posArray)` for count — must use `colorsArray` length ÷ 4 instead (each Color4f = 4 floats)
3. `ReleaseFloatArrayElements(posArray, ...)` — needs `if (posArray != nullptr)` guard

## File to Modify

| File | Description |
|------|-------------|
| `Skija/platform/cc/Shader.cc` | Fix null posArray handling in 4 CS functions |

## Changes

### `_nMakeLinearGradientCS` (line 66-84)

```cpp
// Line 71: Add null check
float* pos = posArray == nullptr ? nullptr : env->GetFloatArrayElements(posArray, nullptr);

// Line 80: Use colorsArray for count (4 floats per Color4f)
SkShader* ptr = SkGradientShader::MakeLinear(pts, ..., pos,
    env->GetArrayLength(colorsArray) / 4, tileMode, interp, localMatrix.get()).release();

// Lines 82: Add null guard on release
if (posArray != nullptr)
    env->ReleaseFloatArrayElements(posArray, pos, 0);
```

### `_nMakeRadialGradientCS` (line 99-116)

Same 3 fixes: null check on Get, count from `colorsArray / 4`, null guard on Release.

### `_nMakeTwoPointConicalGradientCS` (line 131-148)

Same 3 fixes.

### `_nMakeSweepGradientCS` (line 163-end)

Same 3 fixes. Note: the sweep variant uses `env->GetArrayLength(colorsArray)` — change to `colorsArray / 4`.

## After Fix

Rebuild Skija:
```bash
export JAVA_HOME=$(/usr/libexec/java_home) && bb scripts/build-skija.clj
```

## Verification

```bash
clj -M:dev:macos-arm64 -e "(quick-open :howto/gradient-spaces)"
```

Should show gradient bars without crashing. Yellow→Blue in sRGB should have muddy midtones; in Oklab should stay vibrant.
