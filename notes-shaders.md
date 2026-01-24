# Conversation: Can I check? Does skija supports like me writing shaders and it rendering them and also check when I

**Date:** 2026-01-24T13:44:55.906Z
**Project:** /Users/as186073/Downloads/Skija-master
**Messages:** 3

## Table of Contents

1. [Can I check? Does skija supports like me writing shaders and it rendering them and also check when I...](#user-1)

---

## <a id="user-1"></a>User #1

Can I check? Does skija supports like me writing shaders and it rendering them and also check when I mess something in the shaders, mess up something in the shaders, then does the original skier reports errors and this skija reports errors. if skija reports errors too tell me how it does it does it send the error as is or it does one conversion because of java/jvm.

---

**Assistant:**

I'll explore the Skija codebase to answer your questions about shader support and error reporting.

---

**Assistant:**

## Yes, Skija supports custom shaders!

You can write shaders using **SkSL** (Skia Shading Language) through the `RuntimeEffect` class. There are three types:

| Type | Factory Method | Entry Point |
|------|----------------|-------------|
| Shader | `RuntimeEffect.makeForShader(sksl)` | `vec4 main(vec2 coords)` |
| Color Filter | `RuntimeEffect.makeForColorFilter(sksl)` | `vec4 main(vec4 color)` |
| Blender | `RuntimeEffect.makeForBlender(sksl)` | `vec4 main(vec4 src, vec4 dst)` |

You can also use `RuntimeEffectBuilder` for a builder pattern with uniforms and child effects.

---

## Error Reporting

**Yes, Skija reports shader errors** — and it passes them **as-is** from Skia with minimal conversion.

Here's how it works (from `platform/cc/RuntimeEffect.cc:33-40`):

```cpp
SkRuntimeEffect::Result result = SkRuntimeEffect::MakeForShader(skslProper, options);
if (result.errorText.isEmpty()) {
    return ptrToJlong(result.effect.release());
} else {
    env->ThrowNew(java::lang::RuntimeException::cls, result.errorText.c_str());
    return 0;
}
```

**Key points:**
1. Skia's native SkSL compiler produces the error text
2. Skija takes that `errorText` C++ string and directly passes it to a Java `RuntimeException`
3. **No conversion or modification** of the error message — you get Skia's original compiler errors verbatim
4. The only "conversion" is the C++ `char*` → Java String (standard JNI string conversion)

So if you mess up your shader syntax, you'll catch a `RuntimeException` with Skia's exact error message describing what went wrong (syntax errors, type mismatches, invalid signatures, etc.).

---

