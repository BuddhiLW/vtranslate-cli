(ns vtranslate.cli.web.port.filepicker
  "Port (DIP) for asking the host which file the operator wants.

   The panel must be exercisable on a headless machine with no dialog program
   installed, so the thing that pops a chooser is a COLLABORATOR the server is
   given, never a function it reaches for. A test injects a record; production
   injects the native-dialog adapter."
  (:require [hive-dsl.result :as r]))

(defprotocol IFilePicker
  "Ask the operator to choose one file on the host."
  (pick [this opts]
    "Pop a chooser. `opts` may carry :title and :dir (where to open).
     Blocks until the operator answers.
     => (r/ok {:path str}) | (r/err :error/pick-cancelled|... {...})."))

(defn picker?
  "True when `x` satisfies the port."
  [x]
  (satisfies? IFilePicker x))

(defrecord UnavailablePicker [reason]
  IFilePicker
  (pick [_ _] (r/err :error/no-file-picker {:reason reason})))

(def unavailable
  "A picker that refuses every request. The correct default when the host has no
   dialog program — the panel says so instead of hanging. A record, not a reify:
   babashka's `satisfies?` does not recognise a reify'd implementation."
  (->UnavailablePicker "no native file dialog is installed (tried zenity, kdialog, yad, osascript)"))

(defn checked-picker
  "Smart ctor: `x` if it satisfies the port.
   => (r/ok picker) | (r/err :error/invalid-file-picker {...})."
  [x]
  (if (picker? x)
    (r/ok x)
    (r/err :error/invalid-file-picker
           {:reason "value does not satisfy IFilePicker" :type (str (type x))})))
