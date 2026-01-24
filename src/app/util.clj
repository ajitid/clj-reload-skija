(ns app.util
  "Shared utilities for the app namespace.")

(defn cfg
  "Get config value with runtime var lookup (survives hot-reload).
   Uses requiring-resolve to load namespace if needed."
  [var-sym]
  (some-> (requiring-resolve var-sym) deref))
