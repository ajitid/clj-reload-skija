(ns lib.video.hwaccel.vaapi
  "Linux VAAPI zero-copy path: DMA-BUF -> EGLImage -> OpenGL texture.

   This module provides infrastructure for the zero-copy path on Linux
   with Intel and AMD GPUs (via VAAPI):
   1. FFmpeg decodes to VASurface (via VAAPI)
   2. Export VASurface as DMA-BUF fd via vaExportSurfaceHandle
   3. Import DMA-BUF as EGLImage via eglCreateImageKHR
   4. Bind EGLImage to GL texture via glEGLImageTargetTexture2DOES

   NOTE: This requires EGL context (not GLX). SDL3 can create EGL contexts
   on Linux when properly configured.

   Status: Stub - implementation pending."
  (:require [lib.video.hwaccel.detect :as detect]))

;; ============================================================
;; Platform Check
;; ============================================================

(defn available?
  "Check if VAAPI zero-copy path is available.
   Requires Linux with Intel or AMD GPU."
  []
  (and (= (detect/get-platform) :linux)
       (#{:intel :amd} (detect/get-gpu-vendor))
       (detect/available? :vaapi)))

;; ============================================================
;; Stub API
;; ============================================================

(defn bind-vasurface-to-texture!
  "Bind a VAAPI surface to an OpenGL texture via EGL.

   Status: Not yet implemented.

   Would require:
   - JNA bindings for libva (vaExportSurfaceHandle)
   - JNA bindings for EGL (eglCreateImageKHR)
   - GL extension glEGLImageTargetTexture2DOES"
  [texture-id va-surface width height]
  (throw (ex-info "VAAPI zero-copy not yet implemented"
                  {:texture-id texture-id
                   :va-surface va-surface})))

(comment
  "VAAPI zero-copy implementation outline:

   ;; JNA interfaces needed:
   ;; 1. libva.so - VAAPI library
   ;;    - vaExportSurfaceHandle(display, surface, mem_type, flags, descriptor)
   ;;
   ;; 2. libEGL.so - EGL library
   ;;    - eglCreateImageKHR(display, context, target, buffer, attribs)
   ;;    - eglDestroyImageKHR(display, image)
   ;;
   ;; 3. libGLESv2.so or via GL extension
   ;;    - glEGLImageTargetTexture2DOES(target, image)

   ;; DMA-BUF attributes for eglCreateImageKHR:
   (def EGL_LINUX_DMA_BUF_EXT 0x3270)
   (def EGL_WIDTH 0x3057)
   (def EGL_HEIGHT 0x3056)
   (def EGL_LINUX_DRM_FOURCC_EXT 0x3271)
   (def EGL_DMA_BUF_PLANE0_FD_EXT 0x3272)
   (def EGL_DMA_BUF_PLANE0_OFFSET_EXT 0x3273)
   (def EGL_DMA_BUF_PLANE0_PITCH_EXT 0x3274)")
