(ns haywire128.etheric-rpg-engine.cli
  "CLI presentation utilities using Bling. Exclusively styled with a dark, mystical, and minimalist theme
   inspired by 24-bit truecolor definitions:
     --frozen-water: #daffefff (RGB 218, 255, 239)
     --tea-green: #d0ffd6ff (RGB 208, 255, 214)
     --tea-green-2: #d5e2bcff (RGB 213, 226, 188)
     --lilac-ash: #a6979cff (RGB 166, 151, 156)
     --thistle: #d3c0d2ff (RGB 211, 192, 210)"
  (:require [bling.core :as bling]
            [bling.banner :as b-banner]
            [bling.fonts.ansi-shadow :as font]
            [bling.hifi :as hifi]
            [clojure.string :as str]))

;; 24-Bit Truecolor ANSI Escape Codes
(def hex-frozen-water "\u001B[38;2;218;255;239m")
(def hex-tea-green    "\u001B[38;2;208;255;214m")
(def hex-tea-green-2  "\u001B[38;2;213;226;188m")
(def hex-lilac-ash    "\u001B[38;2;166;151;156m")
(def hex-thistle      "\u001B[38;2;211;192;210m")
(def hex-reset        "\u001B[0m")
(def hex-bold         "\u001B[1m")
(def hex-dim          "\u001B[2m")
(def hex-italic       "\u001B[3m")
(def hex-white        "\u001B[38;2;255;255;255m")
(def hex-red          "\u001B[38;2;255;100;100m")

(defn style
  "Return styled string using exact 24-bit truecolor ANSI codes."
  [text & style-keys]
  (let [prefix (apply str (map (fn [k]
                                 (case k
                                   :frozen-water hex-frozen-water
                                   :tea-green    hex-tea-green
                                   :tea-green-2  hex-tea-green-2
                                   :lilac-ash    hex-lilac-ash
                                   :thistle      hex-thistle
                                   :white        hex-white
                                   :red          hex-red
                                   :bold         hex-bold
                                   :dim          hex-dim
                                   :italic       hex-italic
                                   ""))
                               style-keys))]
    (str prefix text hex-reset)))

(defn banner
  "Return a styled ASCII banner with a dark & mystical cool->warm gradient."
  []
  (let [title (b-banner/banner {:text "ETHERIC RPG"
                                :font font/ansi-shadow
                                :gradient-colors [:cool :warm]})
        sub-style (style "        ✦  Where Worlds Awaken  ✦" :bold :italic :thistle)]
    (str title "\n" sub-style "\n")))

(defn print-narrator
  "Print narrative text with styled pacing. Highly atmospheric, the main focus."
  [text]
  (println (style text :italic :white)))

(defn print-header
  "Print a clean, minimal, book-like turn or chapter header in muted lilac-ash."
  [text]
  (println)
  (println (style (str "— " text " —") :dim :lilac-ash))
  (println))

(defn print-system
  "Print out-of-character system messages. Minimal, small, and out of the way in lilac-ash."
  [text]
  (println (style (str "» " text) :lilac-ash)))

(defn print-success
  "Print positive status updates. Small, inline, and out of the way in tea-green."
  [text]
  (println (style (str "✔ " text) :bold :tea-green)))

(defn print-error
  "Print styled error reports. Small, inline, and out of the way."
  [text]
  (println (style (str "✘ " text) :bold :red)))

(defn print-weaver
  "Print World Weaver dialogue. Distinct but compact and elegant in thistle and frozen-water."
  [text]
  (println (style "✦ World Weaver ✦" :bold :thistle))
  (println (style text :italic :frozen-water)))

(defn print-data
  "Print pretty-printed, syntax-colored EDN data."
  [data]
  (hifi/print-hifi data))

(defn print-debug
  "Print styled out-of-character RLM loop debugging messages."
  [text & {:keys [level]}]
  ;; Note: Main RLM loop logs are written to data/debug.log, but this is kept for CLI commands
  (let [color-kw (case level
                   :error :red
                   :success :tea-green
                   :warning :tea-green-2
                   :lilac-ash)]
    (println (style text :dim color-kw))))

(defn input-prompt
  "Display styled input prompt."
  [label]
  (print (style (str label " > ") :bold :thistle))
  (flush))
