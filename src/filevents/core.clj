(ns filevents.core
  (:import (java.nio.file StandardWatchEventKinds)
           (java.io IOException)
           (java.nio.file DirectoryStream
                          Files
                          FileSystems
                          FileVisitResult
                          Path
                          Paths
                          SimpleFileVisitor
                          WatchEvent
                          WatchEvent$Kind
                          WatchKey
                          WatchService)
           (java.nio.file.attribute BasicFileAttributes)
           (java.nio.file LinkOption)
           (java.util Iterator))
  (:require [fs.core :as fs]
            [clojure.set :refer [difference]]))

(def entry-create StandardWatchEventKinds/ENTRY_CREATE)
(def entry-delete StandardWatchEventKinds/ENTRY_DELETE)
(def entry-modify StandardWatchEventKinds/ENTRY_MODIFY)
(def overflow StandardWatchEventKinds/OVERFLOW)

(def ^:dynamic *check-file-interval*
  "Amount of time between checks for modifications."
  300)

(def ^:dynamic *check-dir-interval*
  "Amount of time between checking a directory for new files."
  1000)

(defn fs
  "The default filesystem."
  []
  (FileSystems/getDefault))

(def empty-str-array
  (into-array String []))
                                           
(def nofollow-links
  (into-array LinkOption
              [LinkOption/NOFOLLOW_LINKS]))

(defn path
  "Constructs a Path."
  [path]
  (.getPath (fs) path empty-str-array))

(defn recurse
  "Returns all directories and sub-directories in dir, inclusive."
  [^Path dir]
  (let [children (atom [])]
    (Files/walkFileTree dir
                        (proxy [SimpleFileVisitor] []
                          (preVisitDirectory [^Path dir
                                              ^BasicFileAttributes attrs]
                            (swap! children conj dir)
                            FileVisitResult/CONTINUE)))
    @children))

(defn register!
  "Register a directory with a watch service. Returns a watch key."
  [^WatchService watcher ^Path dir]
  (.register dir watcher
             (into-array WatchEvent$Kind
                         [entry-create entry-delete entry-modify])))


(defn register-recursive!
  "The filesystem is probably faster than us. When a directory is created, we
  might hear about its creation *after* new files were created in that
  directory. We want to emit, at a minimum, :created events for those files.

  Given a watcher, a current keymap, and a newly created directory, ensures
  that directory is fully watched, and returns a keymap of the *newly tracked
  directories only*. That keymap can be used to create synthetic :created events
  for new *files*, and merged into the original keymap."
  ([watcher new-dir]
   (register-recursive! watcher new-dir {}))
  ([watcher new-dir keymap]
   (register-recursive! watcher new-dir keymap {}))
  ([^WatchService watcher ^Path new-dir keymap new-keymap]
   (let [dirs           (recurse new-dir)
         ; Which directories in the new dir have we *not* registered for yet?
         untracked-dirs (remove (set (vals keymap)) dirs)]
     (if (empty? untracked-dirs)
       ; We've successfully tracked all new directories. Return an empty keymap.
       new-keymap
       ; Register untracked directories
       (let [new-keymap (reduce (fn troi [m path]
                                  (assoc m (register! watcher path) path))
                                new-keymap
                                untracked-dirs)]
         (recur watcher
                new-dir
                (merge keymap new-keymap)
                new-keymap))))))

(defn watcher
  "Creates a new watch service."
  []
  (.newWatchService (fs)))

(defrecord Event [path kind])

(defn dir?
  "Is this a directory? (doesn't follow links)"
  [^Path path]
  (Files/isDirectory path nofollow-links))

(defn list-dir
  "Takes a path, and returns Paths of that directory's children. Lazy sequence:
  closes the directory when exhausted."
  ([dir]
   (let [ds (Files/newDirectoryStream dir)]
     (list-dir ds (.iterator ds))))
  ([^DirectoryStream ds ^Iterator i]
   (if (.hasNext i)
     (cons (.next i) (lazy-seq (list-dir ds i)))
     (.close ds))))

(defn event-seq*
  "Given a watcher, and a map of keys to paths, yields a lazy sequence of Event
  records."
  ([^WatchService watcher keymap]
   ; We need to block until a key arrives, so do that lazily.
   (lazy-seq (event-seq* watcher keymap (.take watcher))))

  ([^WatchService watcher keymap watch-key]
   (try
     (when (empty? keymap)
       (throw (RuntimeException. "No files to track.")))

     ; Events for the next key
     (let [dir (or (get keymap watch-key)
                   (throw (RuntimeException. (str "Unknown watch key"
                                                  (pr-str watch-key)))))

           ; Compute all events for this key
           events (doall
                    (for [^WatchEvent event (.pollEvents watch-key)]
                      (let [kind (.kind event)]
                        (if (= overflow kind)
                          (Event. dir :overflow)
                          (Event. (.resolve dir (.context event))
                                  (condp = kind
                                    entry-create :created
                                    entry-delete :deleted
                                    entry-modify :modified))))))

           ; Did we create any directories?
           new-dirs (->> events
                         (keep (fn [event]
                                 (when (and (= :created (:kind event))
                                            (dir? (:path event)))
                                   (:path event))))
                         set)

           ; Register newly created directories
           new-keymap (->> new-dirs
                           (map (fn [path]
                                  (register-recursive! watcher
                                                       path
                                                       keymap)))
                           (reduce merge))

           ; Generate :created events for those new directories, if necessary
           events (concat events
                          (->> new-keymap
                               (mapcat (fn [[watch-key path]]
                                         (let [children
                                               (->> path
                                                    list-dir
                                                    (remove dir?)
                                                    (map #(Event. % :created))
                                                    doall)]

                                           (if (new-dirs path)
                                             ; Already have a create event
                                             children
                                             (cons (Event. path :created)
                                                   children)))))))

           ; Merge newly watched directories into keymap
           keymap (merge keymap new-keymap)]

       (lazy-cat events
                 ; Grab another key from the watcher and recurse
                 (event-seq* watcher
                             (if (.reset watch-key)
                               ; If reset is true, the key is still valid
                               keymap
                               (dissoc keymap watch-key))
                             (.take watcher))))

     (catch Throwable t
       (.reset watch-key)
       (.close watcher)
       (throw t)))))

(defn event-seq
  "Given a directory, returns a lazy sequence of Event records about that
  directory and its children, recursively. Todo: make this closable."
  [^String dir]
  (let [watcher (watcher)
        keymap  (register-recursive! watcher (path dir))]
    (event-seq* watcher keymap)))

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

(defn watch-dir
  "Recursively watch all the files in a directory and watch for
   new files. Executes asynchronously, in a future."
  [^String dir f]
  (let [watcher (watcher)
        keymap  (register-recursive! watcher (path dir))
        events  (event-seq* watcher keymap)
        fut (future
              (try
                (doseq [event events]
                  (try
                    (f (:kind event) (str (:path event)))
                    (catch Throwable t
                      (.printStackTrace t))))
                (catch Throwable t
                  (.printStackTrace t))))]
    {:future fut
     :watcher watcher}))

(defn cancel-watch
  "Cancels a watch."
  [{:keys [future watcher]}]
  (.close ^WatchService watcher)
  (future-cancel future))

(defn watch
  "Set up watcher threads on files. If any directories are included,
   recursively sets up watchers on the files contained in them."
  [f & files]
  (doall (for [file files]
           (if (fs/directory? file)
             (watch-dir file f)
             (watch-file file f)))))
