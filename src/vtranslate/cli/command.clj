(ns vtranslate.cli.command
  "Pure Go-Bonzai-style command tree. A command NODE is plain data; resolution,
   argument binding, and help rendering are pure functions. No IO — the boundary
   (vtranslate.cli) supplies each leaf's :run fn and executes it.

   The CLI runs on babashka (no hive-dsl on the classpath), so the result
   convention here is plain maps: {:ok v} / {:error message}.

   A node:
     {:name     \"translate\"
      :aliases  #{\"tr\"}                ; optional alternate tokens
      :summary  \"one-line description\"
      :usage    \"vtranslate translate <source> <lang> [opts]\"
      :args     [{:name :source :desc \"...\"} ...]      ; required positionals, ordered
      :opts     {:format {:default \"srt\" :enum #{\"srt\" \"vtt\"} :desc \"...\"}}
      :commands [<child-node> ...]       ; branch node (subcommands)
      :run      (fn [{:keys [args opts]}] -> exit-int)}  ; leaf behavior"
  (:require [clojure.string :as str]))

;; --- result helpers ---------------------------------------------------------

(defn ok  [v]   {:ok v})
(defn err [msg] {:error msg})
(defn ok? [r]   (contains? r :ok))

;; --- resolution -------------------------------------------------------------

(defn- matches? [node token]
  (or (= (:name node) token) (contains? (:aliases node) token)))

(defn resolve-command
  "Walk the tree by leading positional tokens. Returns
   {:cmd <node> :path [names...] :rest [remaining-tokens]} — the deepest matched
   node and the tokens past it. Stops at the first token naming no child."
  [root argv]
  (loop [node root, path [(:name root)], args (vec argv)]
    (let [token (first args)
          child (when token (some #(when (matches? % token) %) (:commands node)))]
      (if child
        (recur child (conj path (:name child)) (vec (rest args)))
        {:cmd node :path path :rest args}))))

;; --- argument binding -------------------------------------------------------

(defn- split-tokens
  "Partition tokens into [positionals {opt-name value}] (opt names are strings,
   sans the leading --)."
  [tokens]
  (loop [ts (seq tokens), pos [], opts {}]
    (if-let [t (first ts)]
      (if (str/starts-with? t "--")
        (recur (nnext ts) pos (assoc opts (subs t 2) (fnext ts)))
        (recur (next ts) (conj pos t) opts))
      [pos opts])))

(defn- bind-opts
  "Apply declared optspecs against the raw --opt map: reject unknown options,
   validate :enum membership, fill :default. => {:ok {kw v}} | {:error msg}."
  [optspecs raw-opts]
  (let [declared (set (map name (keys optspecs)))
        unknown  (remove declared (keys raw-opts))]
    (if (seq unknown)
      (err (str "unknown option: --" (first unknown)))
      (loop [specs (seq optspecs), acc {}]
        (if-let [[k spec] (first specs)]
          (let [provided (get raw-opts (name k))
                v        (or provided (:default spec))]
            (if (and provided (:enum spec) (not (contains? (:enum spec) provided)))
              (err (str "invalid value for --" (name k) ": " provided
                        " (expected " (str/join "|" (sort (:enum spec))) ")"))
              (recur (next specs) (assoc acc k v))))
          (ok acc))))))

(defn parse
  "Bind a leaf command's declared :args (required positionals, ordered) + :opts
   (optional, defaulted, optionally :enum-checked) against `tokens`.
   => {:ok {:args {kw v} :opts {kw v}}} | {:error msg}."
  [{:keys [args opts]} tokens]
  (let [[pos raw-opts] (split-tokens tokens)
        argspecs       (vec args)]
    (cond
      (< (count pos) (count argspecs))
      (err (str "missing argument: <" (name (:name (nth argspecs (count pos)))) ">"))

      (> (count pos) (count argspecs))
      (err (str "too many arguments (expected " (count argspecs) ", got " (count pos) ")"))

      :else
      (let [bound (bind-opts opts raw-opts)]
        (if (ok? bound)
          (ok {:args (into {} (map (fn [s v] [(:name s) v]) argspecs pos))
               :opts (:ok bound)})
          bound)))))

;; --- help rendering ---------------------------------------------------------

(defn- opt-line [[k spec]]
  (str "  --" (name k)
       (when (:enum spec) (str " <" (str/join "|" (sort (:enum spec))) ">"))
       "  " (:desc spec)
       (when (:default spec) (str " (default: " (:default spec) ")"))))

(defn help-text
  "Render help for `node` at the resolved `path` (vector of names): summary,
   usage, required args, options, and subcommands — whichever are present."
  [node path]
  (->> (cond-> [(str (str/join " " path) " — " (:summary node))]
         (:usage node)         (into ["" "USAGE:" (str "  " (:usage node))])
         (seq (:args node))    (into (list* "" "ARGS:"
                                            (map #(str "  <" (name (:name %)) ">  " (:desc %))
                                                 (:args node))))
         (seq (:opts node))    (into (list* "" "OPTIONS:" (map opt-line (:opts node))))
         (seq (:commands node)) (into (list* "" "COMMANDS:"
                                             (map #(str "  " (:name %) "  " (:summary %))
                                                  (:commands node)))))
       (str/join "\n")))
