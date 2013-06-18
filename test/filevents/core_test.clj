(ns filevents.core-test
  (:use clojure.test
        filevents.core)
  (:require [fs.core :as fs]))

(deftest watch-test
  ; Ensure files exist.
  (fs/mkdirs "test/testdir")
  (fs/mkdirs "test/testdir2")

  (let [check (atom nil)]
    (watch (fn [kind _] (swap! check (constantly kind))) "test/testdir2/bar" "test/testdir")
    (testing "Watches for created files"
      (fs/touch "test/testdir/foo")
      (Thread/sleep 1500)
      (is (= :created @check)))
    (testing "Watches for modified files"
      (fs/touch "test/testdir/foo")
      (Thread/sleep 1000)
      (is (= :modified @check)))
    (testing "Watches for deleted files"
      (fs/delete "test/testdir/foo")
      (Thread/sleep 1000)
      (is (= :deleted @check)))
    (testing "Works for single files"
      (fs/touch "test/testdir2/bar")
      (Thread/sleep 1000)
      (is (= :modified @check)))))
