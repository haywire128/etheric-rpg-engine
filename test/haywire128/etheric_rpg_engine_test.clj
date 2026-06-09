(ns haywire128.etheric-rpg-engine-test
  (:require [clojure.test :refer :all]
            [haywire128.etheric-rpg-engine :refer :all]))

(deftest greet-returns-string
  (testing "greet with name"
    (is (string? (with-out-str (greet {:name "Aldric"}))))))
