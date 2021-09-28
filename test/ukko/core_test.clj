(ns ukko.core-test
  (:require [clojure.test :refer :all]
            [ukko.core :as ukko]))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 1))))



(deftest transform
  (testing "org"
    (is (= "<h1 id=\"hello-world\">Hello world</h1>\n"
           (ukko/transform :org "* Hello world" {}))))
  (testing "markdown"
    (is (= "<h1 id=\"hello-world\">Hello world</h1>\n"
           (ukko/transform :md "# Hello world" {}))))
  (testing "fleet"
    (is (= "<h1>Hello world</h1>\n"
           (ukko/transform :fleet "<h1><(:title ctx)></h1>\n" {:title "Hello world"}))))
  (testing "subst"
    (is (= "<h1>Hello world</h1>\n"
           (ukko/transform :subst "<h1>$title$</h1>\n" {:title "Hello world"}))))


  )
