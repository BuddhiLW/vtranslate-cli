(ns vtranslate.cli.web.dialog
  "Which native file dialog to run, and how to read what it says. Pure: a dialog
   program is DATA — a binary name, an argv builder, and the exit-code contract
   it reports a cancellation with — so a new one is a row, not a branch.
     choose     - (present? binary) -> the first installed dialog | nil
     command    - dialog + opts -> argv vector
     interpret  - a finished invocation -> Result<{:path p}>"
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]))

(def default-title "Choose a video or subtitle file")

(defn- titled [{:keys [title]}]
  (if (str/blank? title) default-title (str/trim title)))

(defn- zenity-argv [{:keys [dir] :as opts}]
  (cond-> ["zenity" "--file-selection" "--title" (titled opts)]
    (not-empty dir) (conj (str "--filename=" dir "/"))))

(defn- kdialog-argv [{:keys [dir] :as opts}]
  ["kdialog" "--title" (titled opts) "--getopenfilename" (or (not-empty dir) ".")])

(defn- yad-argv [{:keys [dir] :as opts}]
  (cond-> ["yad" "--file" "--title" (titled opts)]
    (not-empty dir) (conj (str "--filename=" dir "/"))))

(defn- osascript-argv [opts]
  ["osascript" "-e"
   (str "POSIX path of (choose file with prompt \"" (titled opts) "\")")])

(def dialogs
  "Known dialog programs, most-preferred first."
  [{:id :zenity    :binary "zenity"    :argv zenity-argv}
   {:id :kdialog   :binary "kdialog"   :argv kdialog-argv}
   {:id :yad       :binary "yad"       :argv yad-argv}
   {:id :osascript :binary "osascript" :argv osascript-argv}])

(defn available
  "Dialogs whose binary `present?` resolves, in preference order."
  [present?]
  (filterv (comp present? :binary) dialogs))

(defn choose
  "The dialog to use. => dialog map | nil when none is installed."
  [present?]
  (first (available present?)))

(defn command
  "The argv that pops `dialog`. `opts` may carry :title and :dir."
  [dialog opts]
  ((:argv dialog) (or opts {})))

(defn parse-selection
  "The chosen path from a dialog's stdout, or nil. Multi-select dialogs join
   with `|`; only the first selection is taken."
  [out]
  (some-> out str/split-lines first (str/split #"\|") first str/trim not-empty))

(defn interpret
  "A finished dialog invocation becomes a Result. A dismissed dialog is
   :error/pick-cancelled, which callers may treat as a no-op rather than a fault.
   => (r/ok {:path p}) | (r/err :error/pick-cancelled|:error/pick-failed {...})"
  [{:keys [exit out err]}]
  (let [path (parse-selection out)]
    (cond
      (and (zero? exit) path) (r/ok {:path path})
      (zero? exit)            (r/err :error/pick-cancelled {:reason "no file chosen"})
      (= 1 exit)              (r/err :error/pick-cancelled {:reason "dialog dismissed"})
      :else                   (r/err :error/pick-failed
                                     {:exit exit
                                      :message (some-> err str str/trim not-empty)}))))
