# Screen Capture Alpha Preservation Analysis

Analysis of `src/lib/window/capture.clj` for alpha channel preservation.

## Alpha Preservation by API

| API                | Format           | Alpha Preserved?                |
| ------------------ | ---------------- | ------------------------------- |
| `screenshot!`      | `:png`           | Yes                             |
| `screenshot!`      | `:jpeg`          | No (JPEG doesn't support alpha) |
| `start-recording!` | `:png` sequence  | Yes                             |
| `start-recording!` | `:mp4` (default) | No                              |

## Key Code Points

**Input side** - Always captures full RGBA (line 120-121):

```clojure
(GL11/glReadPixels 0 0 width height
                   GL11/GL_RGBA GL11/GL_UNSIGNED_BYTE
                   0)
```

**PNG screenshot** - Preserves alpha via FFmpeg (line 215-221):

- Input: `-pix_fmt rgba`
- Output: PNG format natively supports alpha

**MP4 video** - Loses alpha (line 343-344):

```clojure
"-c:v" "libx264"
"-pix_fmt" "yuv420p"  ;; <-- Alpha lost here
```

**PNG sequence** - Preserves alpha (line 323-332):

- Input: `-pix_fmt rgba`
- Output: PNG files preserve alpha

## Summary

The current API **does** preserve alpha in:

- PNG screenshots
- PNG image sequences (`:format :png`)

Alpha is **lost** in:

- MP4 video recording (yuv420p conversion)
- JPEG screenshots (format limitation)

## Future: Video with Alpha

If lossless video with alpha is needed, potential formats to add:

- ProRes 4444: `-c:v prores_ks -profile:v 4444 -pix_fmt yuva444p10le`
- WebM VP9 with alpha: `-c:v libvpx-vp9 -pix_fmt yuva420p`
