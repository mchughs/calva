(ns calva.fmt.formatter
  (:require [cljfmt.core :as cljfmt]
            #_[zprint.core :refer [zprint-str]]
            [calva.js-utils :refer [jsify]]
            [calva.fmt.util :as util]
            [calva.parse :refer [parse-clj-edn]]
            [clojure.string]))

(defn- merge-default-indents
  "Merges onto default-indents.
   The :replace metadata hint allows to replace defaults."
  [indents]
  (if (:replace (meta indents))
    indents
    (merge cljfmt/default-indents indents)))

(def ^:private default-fmt
  {:remove-surrounding-whitespace? true
   :remove-trailing-whitespace? true
   :remove-consecutive-blank-lines? false
   :insert-missing-whitespace? true
   :align-associative? false})

(defn- read-cljfmt
  [s]
  #_(println "*** parsing config")
  (try
    (as-> s $
      (parse-clj-edn $)
      (update $ :indents merge-default-indents)
      (merge default-fmt $))
    (catch js/Error e
      {:error (.-message e)})))

(defn read-cljfmt-js-bridge
  [s]
  (-> s read-cljfmt jsify))

(defn- cljfmt-options
  [{:as config :keys [cljfmt-string]}]
  (-> cljfmt-string
      (read-cljfmt)
      (merge config)))

(defn format-text
  [{:keys [range-text eol config] :as m}]
  (try
    (let [formatted-text (-> range-text
                             (cljfmt/reformat-string (cljfmt-options config))
                             (clojure.string/replace #"\r?\n" eol))]
      (assoc m :range-text formatted-text))
    (catch js/Error e
      (assoc m :error (.-message e)))))

(defn format-text-bridge
  [m]
  (format-text m))

(comment
  {:eol "\n" :all-text "[:foo\n\n(foo)(bar)]" :idx 6}
  (def s "[:foo\n\n(foo)(bar)]")
  #_(def s "(defn\n0\n#_)")
  (format-text #_s
               {:range-text s
                :eol "\n"
                :config {:remove-surrounding-whitespace? false
                         :remove-trailing-whitespace? false
                         :remove-consecutive-blank-lines? false
                         :align-associative? true}}))

(defn current-line-empty?
  "Figure out if `:current-line` is empty"
  [{:keys [current-line]}]
  (some? (re-find #"^\s*$" current-line)))


(defn indent-before-range
  "Figures out how much extra indentation to add based on the length of the line before the range"
  [{:keys [all-text range]}]
  (let [start (first range)
        end (last range)]
    (if (= start end)
      0
      (-> (subs all-text 0 (first range))
          (util/split-into-lines)
          (last)
          (count)))))


(defn add-head-and-tail
  "Splits `:all-text` at `:idx` in `:head` and `:tail`"
  [{:keys [all-text idx] :as m}]
  (-> m
      (assoc :head (subs all-text 0 idx)
             :tail (subs all-text idx))))


(defn add-current-line
  "Finds the text of the current line in `text` from cursor position `index`"
  [{:keys [head tail] :as m}]
  (-> m
      (assoc :current-line
             (str (second (re-find #"\n?(.*)$" head))
                  (second (re-find #"^(.*)\n?" tail))))))


(defn- normalize-indents
  "Normalizes indents based on where the text starts on the first line"
  [{:keys [range-text eol] :as m}]
  (let [indent-before (apply str (repeat (indent-before-range m) " "))
        lines (clojure.string/split range-text #"\r?\n(?!\s*;)" -1)]
    (assoc m :range-text (clojure.string/join (str eol indent-before) lines))))


(defn index-for-tail-in-range
  "Find index for the `tail` in `text` disregarding whitespace"
  [{:keys [range-text range-tail on-type] :as m}]
  (let [leading-space-length (count (re-find #"^[ \t]*" range-tail))
        space-sym (str "@" (gensym "ESPACEIALLY") "@")
        tail-pattern (-> range-tail
                         (clojure.string/replace #"[\]\)\}\"]" (str "$&" space-sym))
                         (util/escape-regexp)
                         (clojure.string/replace #"^[ \t]+" "")
                         (clojure.string/replace #"\s+" "\\s*")
                         (clojure.string/replace space-sym " ?"))
        tail-pattern (if (and on-type (re-find #"^\r?\n" range-tail))
                       (str "(\\r?\\n)+" tail-pattern)
                       tail-pattern)
        pos (util/re-pos-first (str "[ \\t]{0," leading-space-length "}" tail-pattern "$") range-text)]
    (assoc m :new-index pos)))

(defn format-text-at-range
  "Formats text from all-text at the range"
  [{:keys [all-text range idx] :as m}]
  (let [indent-before (indent-before-range m)
        padding (apply str (repeat indent-before " "))
        range-text (subs all-text (first range) (last range))
        padded-text (str padding range-text)
        range-index (- idx (first range))
        tail (subs range-text range-index)
        formatted-m (format-text (assoc m :range-text padded-text))]
    (-> (assoc formatted-m :range-text (subs (:range-text formatted-m) indent-before))
        (assoc :range-tail tail))))

(defn format-text-at-range-bridge
  [m]
  (format-text-at-range m))

(comment
  (format-text-at-range {:all-text "  '([]\n[])"
                         :idx 7
                         :on-type true
                         :head "  '([]\n"
                         :tail "[])"
                         :current-line "[])"
                         :range [4 9]})
  (format-text-at-range {:eol "\n"
                         :all-text "[:foo\n\n(foo)(bar)]"
                         :idx 6
                         :range [0 18]}))


(defn add-indent-token-if-empty-current-line
  "If `:current-line` is empty add an indent token at `:idx`"
  [{:keys [head tail range] :as m}]
  (let [indent-token "0"]
    (if (current-line-empty? m)
      (assoc m
             :all-text (str head indent-token tail)
             :range [(first range) (inc (last range))])
      m)))


(defn remove-indent-token-if-empty-current-line
  "If an indent token was added, lets remove it. Not forgetting to shrink `:range`"
  [{:keys [range-text range new-index] :as m}]
  (if (current-line-empty? m)
    (assoc m :range-text (str (subs range-text 0 new-index) (subs range-text (inc new-index)))
           :range [(first range) (dec (second range))])
    m))


(defn format-text-at-idx
  "Formats the enclosing range of text surrounding idx"
  [m]
  (-> m
      (add-head-and-tail)
      (add-current-line)
      (add-indent-token-if-empty-current-line)
      #_(enclosing-range)
      (format-text-at-range)
      (index-for-tail-in-range)
      (remove-indent-token-if-empty-current-line)))

(defn format-text-at-idx-bridge
  [m]
  (format-text-at-idx m))

(defn format-text-at-idx-on-type
  "Relax formating some when used as an on-type handler"
  [m]
  (-> m
      (assoc :on-type true)
      (assoc-in [:config :remove-surrounding-whitespace?] false)
      (assoc-in [:config :remove-trailing-whitespace?] false)
      (assoc-in [:config :remove-consecutive-blank-lines?] false)
      (format-text-at-idx)))

(defn format-text-at-idx-on-type-bridge
  [m]
  (format-text-at-idx-on-type m))

(comment
  (:range-text (format-text-at-idx-on-type {:all-text "  '([]\n[])" :idx 7})))

(comment
  {:remove-surrounding-whitespace? false
   :remove-trailing-whitespace? false
   :remove-consecutive-blank-lines? false
   :insert-missing-whitespace? true
   :align-associative? true})
