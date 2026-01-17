# Gesture Handling System

A Flutter-inspired Gesture Arena system for layered gesture handling in Clojure/JWM/Skija applications.

## Overview

This system provides:
- **Overlapping hit areas** with layer/z-index priority
- **Modal/overlay blocking** for popups and dialogs
- **Priority-based gestures** (tap vs long-press vs drag)
- **Nested draggables** with axis-aware conflict resolution

## Architecture

### Event Flow

```
Pointer Down → Hit Test → Create Recognizers → Arena Tracking
                ↓
Pointer Move → Update Recognizers → Check Victory/Failure → Resolve
                ↓
Pointer Up → Sweep Arena (force resolution) → Deliver Gesture → Reset
```

### Layer Priority

Events go to the highest layer first:

```
:modal      (highest priority - blocks everything below)
:overlay    (UI controls, panels)
:content    (main content)
:background (lowest priority)
```

### Recognizer State Machine

```
              ┌──────────┐
              │ possible │←─────────────────┐
              └──────────┘                  │
                   │                        │
     ┌─────────────┼─────────────┐          │
     │             │             │          │
     ▼             ▼             ▼          │
 ┌───────┐   ┌─────────┐   ┌────────┐       │
 │ began │──▶│ changed │──▶│ ended  │       │
 └───────┘   └─────────┘   └────────┘       │
     │             │                        │
     ▼             ▼                        │
 ┌────────┐  ┌───────────┐                  │
 │ failed │  │ cancelled │──────────────────┘
 └────────┘  └───────────┘
```

### Recognizer Priorities

| Recognizer | Priority | Declares Victory When | Fails When |
|------------|----------|----------------------|------------|
| Drag | 50 | Movement > 10px | Never (fallback) |
| Long-press | 40 | Held > 500ms | Pointer up early, moved > 10px |
| Tap | 30 | Pointer up (default) | Movement > 10px, duration > 300ms |

## File Structure

```
src/lib/gesture/
├── state.clj       # defonce: arena atom, hit-targets registry
├── hit_test.clj    # Hit testing with layer + z-index sorting
├── recognizers.clj # Drag, tap, long-press implementations
├── arena.clj       # Resolution algorithm
└── api.clj         # Public API

src/app/
└── gestures.clj    # App-specific target registrations
```

## API Reference

### Registering Targets

```clojure
(require '[lib.gesture.api :as gesture])

(gesture/register-target!
 {:id         :my-element          ; Required: unique identifier
  :layer      :content             ; :modal | :overlay | :content | :background
  :z-index    10                   ; Within-layer priority (higher = on top)
  :bounds-fn  (fn [ctx] [x y w h]) ; Returns [x y width height] given context
  :gesture-recognizers [:drag :tap :long-press]  ; Which gestures to recognize
  :handlers   {:on-tap        (fn [event] ...)
               :on-long-press (fn [event] ...)
               :on-drag-start (fn [event] ...)
               :on-drag       (fn [event] ...)
               :on-drag-end   (fn [event] ...)}})
```

### Unregistering Targets

```clojure
(gesture/unregister-target! :my-element)

;; Or clear all
(gesture/clear-targets!)
```

### Modal System

```clojure
;; Block all layers below :modal
(gesture/push-modal! :modal)

;; Remove blocking
(gesture/pop-modal!)
```

### Gesture Event Structure

Handlers receive an event map:

```clojure
{:type      :drag-changed    ; Event type
 :target-id :my-element      ; Which target
 :pointer   {:x 150 :y 200}  ; Current pointer position
 :start     {:x 100 :y 180}  ; Where gesture started
 :delta     {:x 50 :y 20}    ; Distance from start
 :time      1234             ; Duration in ms
 :target    {...}}           ; Full target definition
```

## Usage Examples

### Basic Draggable Element

```clojure
(gesture/register-target!
 {:id :draggable-box
  :layer :content
  :z-index 10
  :bounds-fn (fn [_ctx]
               [@box-x @box-y 100 100])
  :gesture-recognizers [:drag]
  :handlers {:on-drag-start (fn [e]
                              (reset! drag-offset-x (- @box-x (get-in e [:pointer :x])))
                              (reset! drag-offset-y (- @box-y (get-in e [:pointer :y]))))
             :on-drag (fn [e]
                        (reset! box-x (+ (get-in e [:pointer :x]) @drag-offset-x))
                        (reset! box-y (+ (get-in e [:pointer :y]) @drag-offset-y)))
             :on-drag-end (fn [_e]
                            (println "Drag ended!"))}})
```

### Button with Tap and Long-Press

```clojure
(gesture/register-target!
 {:id :action-button
  :layer :content
  :z-index 20
  :bounds-fn (fn [_ctx] [100 100 120 40])
  :gesture-recognizers [:tap :long-press]
  :handlers {:on-tap (fn [_e]
                       (println "Button tapped - primary action"))
             :on-long-press (fn [_e]
                              (println "Long press - show context menu"))}})
```

### Modal Dialog

```clojure
(defn show-dialog! []
  ;; Register dialog background (captures all clicks)
  (gesture/register-target!
   {:id :dialog-backdrop
    :layer :modal
    :z-index 0
    :bounds-fn (fn [ctx] [0 0 (:window-width ctx) (:window-height ctx)])
    :gesture-recognizers [:tap]
    :handlers {:on-tap (fn [_e] (close-dialog!))}})

  ;; Register dialog content
  (gesture/register-target!
   {:id :dialog-content
    :layer :modal
    :z-index 10
    :bounds-fn (fn [ctx]
                 (let [w 300 h 200
                       cx (/ (:window-width ctx) 2)
                       cy (/ (:window-height ctx) 2)]
                   [(- cx (/ w 2)) (- cy (/ h 2)) w h]))
    :gesture-recognizers [:tap]
    :handlers {:on-tap (fn [_e]
                         ;; Don't close when tapping dialog itself
                         nil)}})

  ;; Block events to layers below
  (gesture/push-modal! :modal))

(defn close-dialog! []
  (gesture/unregister-target! :dialog-backdrop)
  (gesture/unregister-target! :dialog-content)
  (gesture/pop-modal!))
```

### Overlapping Elements (Panel over Content)

```clojure
;; Panel slider - in overlay layer, checked first
(gesture/register-target!
 {:id :slider
  :layer :overlay
  :z-index 10
  :bounds-fn (fn [ctx]
               (when @panel-visible?
                 [(:panel-x ctx) 50 200 20]))
  :gesture-recognizers [:drag]
  :handlers {:on-drag (fn [e]
                        (reset! slider-value
                                (calculate-value (get-in e [:pointer :x]))))}})

;; Background element - in content layer, only gets events if overlay misses
(gesture/register-target!
 {:id :canvas
  :layer :content
  :z-index 0
  :bounds-fn (fn [ctx] [0 0 (:window-width ctx) (:window-height ctx)])
  :gesture-recognizers [:drag :tap]
  :handlers {:on-drag (fn [e] (pan-canvas e))
             :on-tap (fn [e] (place-point e))}})
```

### Nested Draggables (Scroll View with Draggable Cards)

```clojure
;; Outer scroll container - vertical drag
(gesture/register-target!
 {:id :scroll-view
  :layer :content
  :z-index 5
  :bounds-fn (fn [_ctx] [0 0 400 600])
  :gesture-recognizers [:drag]
  :handlers {:on-drag (fn [e]
                        ;; Only respond to vertical movement
                        (when (> (Math/abs (get-in e [:delta :y]))
                                 (Math/abs (get-in e [:delta :x])))
                          (scroll-by (get-in e [:delta :y]))))}})

;; Inner card - horizontal drag (higher z-index, checked first)
(gesture/register-target!
 {:id :card-1
  :layer :content
  :z-index 10
  :bounds-fn (fn [_ctx] [20 @card-y 360 80])
  :gesture-recognizers [:drag]
  :handlers {:on-drag-start (fn [_e] (reset! dragging-card? true))
             :on-drag (fn [e]
                        ;; Only respond to horizontal movement
                        (when (> (Math/abs (get-in e [:delta :x]))
                                 (Math/abs (get-in e [:delta :y])))
                          (reset! card-x (+ @card-start-x (get-in e [:delta :x])))))
             :on-drag-end (fn [_e]
                            (reset! dragging-card? false)
                            (snap-card-back!))}})
```

## Integration with App

### In `app/core.clj`

The gesture system is wired up in three places:

```clojure
;; 1. In init - register targets
(defn init []
  ;; ... other init code ...
  (when-let [register-gestures! (requiring-resolve 'app.gestures/register-gestures!)]
    (register-gestures!)))

;; 2. In tick - check long-press timers
(defn tick [dt]
  ;; ... other tick code ...
  (when-let [check-long-press! (requiring-resolve 'lib.gesture.api/check-long-press!)]
    (check-long-press!)))

;; 3. In event listener - handle mouse events
EventMouseButton
(when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-button)]
  (handle-fn event {:scale @state/scale
                    :window-width @state/window-width}))

EventMouseMove
(when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-move)]
  (handle-fn event {:scale @state/scale
                    :window-width @state/window-width}))
```

## Hot-Reload Compatibility

The system follows clj-reload best practices:

| Namespace | Declaration | Reload Behavior |
|-----------|-------------|-----------------|
| `lib.gesture.state` | `defonce` | Arena, registry **persist** |
| `lib.gesture.hit-test` | `defn` | Logic **updates** |
| `lib.gesture.recognizers` | `defn` | Logic **updates** |
| `lib.gesture.arena` | `defn` | Logic **updates** |
| `lib.gesture.api` | `defn` | API **updates** |
| `app.gestures` | `defn` | Handlers **update** on reload |

Re-registering targets on reload:
```clojure
;; In app.gestures/register-gestures!
(when-let [clear! (requiring-resolve 'lib.gesture.api/clear-targets!)]
  (clear!))
;; ... then register all targets fresh
```

## Design Inspiration

This system is inspired by:

- **Flutter Gesture Arena** - Competing recognizers, self-elimination, victory declaration
- **Web DOM Events** - Capture/bubble phases, stopPropagation
- **React Native Gesture Handler** - Race, Simultaneous, Exclusive composition

### Key Concepts from Flutter

1. **Hit Test**: Find all widgets under pointer
2. **Gesture Recognizers**: Each creates recognizers that compete
3. **Arena Resolution**: Self-elimination or victory declaration
4. **Disambiguation**: Movement thresholds, timing constraints
