(ns filevents.core
  (:require [fs.core :as fs]
            [clojure.set :refer [difference]]))

(def ^:dynamic *check-file-interval*
  "Amount of time between checks for modifications."
  300)

(def ^:dynamic *check-dir-interval*
  "Amount of time between checking a directory for new files."
  1000)

(defn watch-file
  "Set up a thread that watches a file for two events: deletion
   and modification. If the file is modified within the interval,
   fires f with :modified. If it doesn't exist after each interval,
   fires f with :deleted and ends the loop."
  [file f]
  (future
    (loop [time (fs/mod-time file)]
      (Thread/sleep *check-file-interval*)
      (let [new-time (fs/mod-time file)]
        (cond
         (not (fs/exists? file)) (f :deleted file)
         (> new-time time)       (do (f :modified file)
                                     (recur new-time))
         :else                   (recur new-time))))))

(defn ^:private get-file-set
  "Get a set of non-directory files in a directory recursively."
  [dir]
  (->> (fs/file dir)
       (file-seq)
       (filter fs/file?)
       (set)))

(defn watch-dir
  "Recursively watch all the files in a directory and watch for
   new files."
  [dir f]
  (let [files (get-file-set dir)]
    (doseq [file files]
      (watch-file file f))
    (future
      (loop [old files]
        (Thread/sleep *check-dir-interval*)
        (let [files (get-file-set dir)
              new-files (difference files old)]
          (doseq [file new-files]
            (future (f :created file))
            (watch-file file f))
          (recur files))))))

(defn watch
  "Set up watcher threads on files. If any directories are included,
   recursively sets up watchers on the files contained in them."
  [f & files]
  (doseq [file files]
    (if (fs/directory? file)
      (watch-dir file f)
      (watch-file file f))))
