(ns vtranslate.cli.web.adapters.native-dialog
  "IFilePicker over the host's own file dialog. The panel serves loopback only,
   so the browser and this process share a machine and a display — the chooser
   that opens is the operator's real file manager, and it yields an absolute
   path, which a browser file input cannot."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [vtranslate.cli.web.dialog :as dialog]
            [vtranslate.cli.web.port.filepicker :as port]))

(defn on-path?
  "Whether `binary` resolves on PATH."
  [binary]
  (some? (fs/which binary)))

(defrecord NativeDialog [dialog]
  port/IFilePicker
  (pick [_ opts]
    (let [{:keys [out err exit]}
          @(apply p/process
                  {:out :string :err :string}
                  (dialog/command dialog opts))]
      (dialog/interpret {:exit exit :out out :err err}))))

(defn make-picker
  "The production picker: the first dialog program installed on the host, or the
   refusing default when there is none."
  ([] (make-picker on-path?))
  ([present?]
   (if-let [d (dialog/choose present?)]
     (->NativeDialog d)
     port/unavailable)))

(defn describe
  "Which dialog the host would use, for the health view. => keyword | nil."
  ([] (describe on-path?))
  ([present?] (:id (dialog/choose present?))))
