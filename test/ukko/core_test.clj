(ns ukko.core-test
  (:require [clojure.test :refer :all]
            [ukko.core :as ukko]))

;; TODO: `(deftest debounce

(deftest transform
  (testing "org"
    (is (= "<h1 id=\"hello-world\">Hello world</h1>\n"
           (ukko/transform :org "* Hello world" {}))))
  (testing "markdown"
    (is (= "<h1 id=\"hello-world\">Hello world</h1>\n"
           (ukko/transform :md "# Hello world" {}))))
  (testing "fleet"
    (is (= "<h1>Hello world</h1>\n"
           (ukko/transform :fleet "<h1><(:title ctx)></h1>\n" {:title "Hello world"})))))

;; TODO: `(deftest find-files

(deftest parse-file
  (testing "with frontmatter"
    (is (= {:some "frontmatter"
            :path "test/fixtures/sample.md"
            :template "# some\n## markdown\n"}
           (ukko/parse-file "test/fixtures/sample.md"))))
  (testing "without frontmatter"
    (is (= {:format "copy"
            :path "test/fixtures/sample-without-frontmatter.md"}
           (ukko/parse-file "test/fixtures/sample-without-frontmatter.md"))))
  (testing "invalid frontmatter"
    (is (= {:format "copy"
            :path "test/fixtures/sample-with-invalid-frontmatter.md"}
           (ukko/parse-file "test/fixtures/sample-with-invalid-frontmatter.md"))))
  (testing "edge case: yaml document delimiter in template"
    (is (= {:some "frontmatter"
            :path "test/fixtures/yml-doc-delim-in-template.md"
            :template "# some markdown\n---\n## yaml doc delim above\n"}
           (ukko/parse-file "test/fixtures/yml-doc-delim-in-template.md")))))

;; TODO: not testing process, this should be refactored to use transform instead

(deftest make-id
  (testing "regular case"
    (is (= "hello/world"
           (ukko/make-id {:path "base/hello/world.md"} "base"))))
  (testing "with dot"
    (is (= "hello/worl.d"
           (ukko/make-id {:path "base/hello/worl.d.md"} "base")))))

;; TODO: maybe `(deftest apply-layout

;; TODO: maybe `(deftest apply-layouts

(deftest add-target
  (testing "regular case"
    (let [artifact {:id "hello/world"
                    :target-path "public"
                    :target-extension ".html"}]
      (is (= (assoc artifact :target "public/hello/world.html")
             (ukko/add-target artifact))))))

(deftest add-id
  (testing "regular case"
    (let [artifact {:path "base/hello/world.md"}]
      (is (= (assoc artifact :id "hello/world")
             (ukko/add-id "base" artifact))))))

(deftest add-canonical-link
  (testing "regular case"
    (let [artifact {:id "hello/world"
                    :target-extension ".html"}]
      (is (= (assoc artifact :canonical-link "/hello/world.html")
             (ukko/add-canonical-link artifact))))))

(deftest add-word-count
  (testing "regular case"
    (let [artifact {:template "Hello world."}]
      (is (= (assoc artifact :word-count 2)
             (ukko/add-word-count artifact)))))
  (testing "no words, no word-count"
      (is (= {}
             (ukko/add-word-count {})))))

(deftest add-ttr
  (testing "regular case"
    (let [artifact {:word-count 180}]
      (is (= (assoc artifact :ttr 1)
             (ukko/add-ttr artifact))))
    (let [artifact {:word-count 181}]
      (is (= (assoc artifact :ttr 2)
             (ukko/add-ttr artifact))))
    (let [artifact {:word-count 1}]
      (is (= (assoc artifact :ttr 1)
             (ukko/add-ttr artifact)))))
  (testing "no word-count, no ttr"
    (is (= {}
           (ukko/add-ttr {})))))

;; TODO: maybe `(deftest add-date-published-rfc-3339

;; TODO: maybe `(deftest add-date-published-rfc-822

;; TODO: maybe `(deftest fix-format

;; TODO: maybe `(deftest add-text

;; TODO: maybe `(deftest add-preview

;; TODO: `(deftest process-artifact

;; TODO: `(deftest process-artifact-id

;; TODO: `(deftest cartesian-product

;; TODO: `(deftest modify-id

;; TODO: `(deftest collection-type

;; TODO: `(deftest analyze-artifact

;; TODO: `(deftest sort-key

;; TODO: `(deftest sanitize-id

;; TODO: `(deftest add-data

;; TODO: `(deftest add-layouts

;; TODO: `(deftest add-files

;; TODO: `(deftest add-artifacts

;; TODO: maybe `(deftest generate!

;; TODO: maybe `(deftest main
