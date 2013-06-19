(ns filevents.core-test
  (:use clojure.test
        filevents.core)
  (:import (filevents.core Event))
  (:require [fs.core :as fs]))

(defmacro with-dir
  "Creates a fresh directory to test with."
  [dir & body]
  `(do
     (fs/delete-dir ~dir)
     (fs/mkdirs ~dir)
     ~@body))

(deftest recurse-test
  (with-dir "test/tmp"
    (fs/touch "test/tmp/file")
    (fs/mkdirs "test/tmp/sub/dir")
    (fs/touch "test/tmp/sub/file2")
    (is (= ["test/tmp" "test/tmp/sub" "test/tmp/sub/dir"]
           (map str (recurse (path "test/tmp")))))))

(deftest list-dir-test
  (with-dir "test/tmp"
    (fs/touch "test/tmp/a")
    (fs/mkdirs "test/tmp/sub/dir/cat")
    (is (= (set (list-dir (path "test/tmp")))
           (set [(path "test/tmp/a")
                 (path "test/tmp/sub")])))))

(defn event [str-path kind]
  (Event. (path str-path) kind))

(defn simple-event-seq
  "Like event-seq, but yields [string-path kind] pairs instead."
  [dir]
  (map (fn [event]
         [(str (:path event)) (:kind event)])
       (event-seq dir)))

(deftest event-seq-test
  (testing "create"
    (with-dir "test/tmp"
      (let [events (simple-event-seq "test/tmp")]
        (fs/touch "test/tmp/file")
        (is (= (first events)
               ["test/tmp/file" :created])))))

  (testing "modify"
    (with-dir "test/tmp"
      (fs/touch "test/tmp/file")
      (let [events (simple-event-seq "test/tmp")]
        (fs/touch "test/tmp/file")
        (is (= (first events)
                ["test/tmp/file" :modified])))))

  (testing "delete"
    (with-dir "test/tmp"
      (fs/touch "test/tmp/file")
      (let [events (simple-event-seq "test/tmp")]
        (fs/delete "test/tmp/file")
        (is (= (first events)
               ["test/tmp/file" :deleted])))))

  (testing "multiple events"
    (with-dir "test/tmp"
      (let [events (simple-event-seq "test/tmp")]
        (fs/touch "test/tmp/a")
        (fs/touch "test/tmp/b")
        (fs/touch "test/tmp/b")
        (fs/delete "test/tmp/b")
        (fs/delete "test/tmp/a")
        (is (= (take 5 events)
               [["test/tmp/a" :created]
                ["test/tmp/b" :created]
                ["test/tmp/b" :modified]
                ["test/tmp/b" :deleted]
                ["test/tmp/a" :deleted]])))))

  (testing "multiple dirs"
    (with-dir "test/tmp"
      (fs/mkdir "test/tmp/a")
      (fs/mkdir "test/tmp/b")
      (let [events (simple-event-seq "test/tmp")]
        (fs/touch "test/tmp/a/f")
        (fs/touch "test/tmp/b/f")
        (fs/delete "test/tmp/b/f")
        (fs/delete "test/tmp/a/f")
        (is (= (set (take 4 events))
                   ; Note that these may be re-ordered since we poll keys at a
                   ; time.
               (set [["test/tmp/a/f" :created]
                     ["test/tmp/a/f" :deleted]
                     ["test/tmp/b/f" :created]
                     ["test/tmp/b/f" :deleted]]))))))

  (testing "new dirs"
    (with-dir "test/tmp"
      (let [events (simple-event-seq "test/tmp")]
        ; Make some subdirs
        (fs/mkdirs "test/tmp/a/b/c")
        (fs/touch "test/tmp/a/b/c/file")
        (is (= (take 4 events)
               [["test/tmp/a" :created]
                ["test/tmp/a/b/c" :created]
                ["test/tmp/a/b/c/file" :created]
                ["test/tmp/a/b" :created]]))

        ; Change a dir
        (fs/touch "test/tmp/a/b")
        (is (= (nth events 4) ["test/tmp/a/b" :modified]))

        ; Delete a dir
        (fs/delete-dir "test/tmp/a/b")
        (is (= (take 3 (drop 5 events))
               [["test/tmp/a/b/c/file" :deleted]
                ["test/tmp/a/b/c" :deleted]
                ["test/tmp/a/b" :deleted]]))))))

(deftest watch-test
  (let [changes (atom [])]
    (with-dir "test/tmp"
      (with-dir "test/tmp2"
        (watch (fn [kind file]
                 (swap! changes conj [kind file]))
               "test/tmp2/bar" "test/tmp")

        (fs/touch "test/tmp/tmp")
        (fs/touch "test/tmp/tmp")
        (fs/delete "test/tmp/tmp")

        ; Single file
        (fs/touch "test/tmp2/bar")

        (Thread/sleep 1500)
        (is (= @changes
               [[:created "test/tmp/tmp"]
                [:modified "test/tmp/tmp"]
                [:deleted "test/tmp/tmp"]
                [:modified "test/tmp2/bar"]]))))))
