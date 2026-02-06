What is Spectral Blending?

Spectral blending is pigment-based color mixing using Kubelka-Munk theory — a physics model of how light interacts with layered pigmented materials (paint, ink, etc.).

The key difference from normal RGB blending:

```
┌───────────────┬─────────────────────┬─────────────────────────────┐
│ Operation     │ RGB mix()           │ Spectral spectral_mix()     │
├───────────────┼─────────────────────┼─────────────────────────────┤
│ Blue + Yellow │ Gray/muddy green    │ Green (like paint!)         │
├───────────────┼─────────────────────┼─────────────────────────────┤
│ Red + Blue    │ Dark magenta        │ Purple                      │
├───────────────┼─────────────────────┼─────────────────────────────┤
│ Behavior      │ Additive light math │ Subtractive pigment physics │
└───────────────┴─────────────────────┴─────────────────────────────┘
```

The algorithm:

1. Converts sRGB colors into 38-band spectral reflectance curves (simulating wavelengths of light)
2. Applies Kubelka-Munk absorption/scattering math to mix reflectance curves (like mixing physical pigments)
3. Converts back through CIE XYZ to sRGB

The OPENRNDR BlendSpectral is a 2-texture GPU filter — it blends two input images pixel-by-pixel using this spectral math, with a fill parameter (0.0–1.0) controlling the mix ratio and
optional alpha clipping.

The underlying GLSL implementation comes from https://github.com/rvanwijnen/spectral.js by Ronald van Wijnen (MIT licensed), which provides a self-contained spectral.glsl shader (~38
spectral samples, pre-computed coefficients).

Would it be useful for your project?

Yes, potentially quite useful. A few angles:

1. Easy to port — The entire algorithm is a single GLSL fragment shader (~100-200 lines of self-contained code with pre-computed LUT constants). Since you're already running Skija on
   OpenGL/Metal with custom shaders, you could integrate spectral.glsl as a Skia RuntimeEffect (SKSL) or as a raw GL shader in your render pipeline.
2. Art/creative tool value — For a creative coding / prototyping system like yours, spectral blending is a premium feature. It makes color gradients, particle trails, and overlapping
   shapes look far more natural and painterly than standard alpha blending. If any of your playground examples involve color mixing, layered drawing, or generative art, this is a big visual
   upgrade.
3. Low cost — It's a pure shader computation (no textures/LUTs needed, unlike Mixbox). The 38-sample spectral evaluation is heavier than a simple mix() but totally fine for per-pixel
   fragment work at real-time rates.
4. Practical use cases in your system:
   - A spectral-blend mode for layered drawing in examples
   - A post-processing filter (like your capture/recording pipeline) for compositing
   - Spring/decay animations with color interpolation that looks physically correct

The main caveat is that SKSL (Skia's shader language) is a subset of GLSL, so you'd need minor syntax tweaks when porting, but the math is all standard vec3/float operations that SKSL
supports fully.

Sources:

- https://github.com/rvanwijnen/spectral.js
- https://github.com/scrtwpns/mixbox
- https://github.com/STVND/davis-pigment-mixing
- https://github.com/openrndr/orx/blob/master/orx-fx/README.md
