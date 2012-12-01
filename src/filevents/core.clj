(ns filevents.core
  (:require [fs.core :as fs]))

(def ^:dynamic *check-interval*
  "Amount of time between checks for modifications."
  300)

(defn watch-file
  "Set up a thread that watches a file for two events: deletion
   and modification. If the file is modified within the interval,
   fires f with :modified. If it doesn't exist after each interval,
   fires f with :deleted and ends the loop."
  [file f]
  (future
    (loop [time (fs/mod-time file)]
      (Thread/sleep *check-interval*)
      (let [new-time (fs/mod-time file)]
        (cond
         (not (fs/exists? file)) (f :deleted file)
         (> new-time time)       (do (f :modified file)
                                     (recur new-time))
         :else                   (recur new-time))))))

(defn watch
  "Set up watcher threads on files. If any directories are included,
   recursively sets up watchers on the files contained in them."
  [f & files]
  (doall
   (flatten
    (for [file files]
      (if (fs/directory? file)
        (map #(watch-file % f)
             (filter fs/file? (file-seq (fs/file file))))
        (watch-file file f))))))