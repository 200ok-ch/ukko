(ns ukko.core-test
  (:require [clojure.test :refer :all]
            [ukko.core :as ukko]))

;; TODO: `(deftest debounce

(deftest transform
  (testing "org"
    (is (= "<h1 id=\"hello-world\">Hello world</h1>\n"
           (ukko/transform :org "* Hello world" {}))))
  (testing "markdown"
    (is (= "<h1><a href=\"#hello-world\" id=\"hello-world\">Hello world</a></h1>\n"
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
           (-> (ukko/parse-file "test/fixtures/sample.md")
               (dissoc :mtime)))))
  (testing "skip files without frontmatter"
    (is (= nil
           (ukko/parse-file "test/fixtures/sample-without-frontmatter.md"))))
  (testing "skip files with invalid frontmatter"
    (is (= nil
           (ukko/parse-file "test/fixtures/sample-with-invalid-frontmatter.md"))))
  (testing "edge case: yaml document delimiter in template"
    (is (= {:some "frontmatter"
            :path "test/fixtures/yml-doc-delim-in-template.md"
            :template "# some markdown\n---\n## yaml doc delim above\n"}
           (-> (ukko/parse-file "test/fixtures/yml-doc-delim-in-template.md")
               (dissoc :mtime))))))

;; TODO: not testing `process`, this should be refactored to use `transform` instead

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

(deftest cartesian-product
  (testing "regular case"
    (are [x y] (= x (ukko/cartesian-product y))
      [[]] []
      [[:a]] [[:a]]
      [[:a] [:b]] [[:a :b]]
      [[:a :c] [:b :c]] [[:a :b] [:c]]
      [[:a :c] [:b :c] [:a :d] [:b :d]] [[:a :b] [:c :d]])))

(deftest modify-id
  (testing "regular case"
    (is (= "keep/this/with-me"
           (ukko/modify-id "keep/this/replace-this" "with-me")))))

(deftest collection-type
  (testing "regular case"
    (are [x y] (= x (ukko/collection-type y))
      :nil nil
      :sequential []
      :sequential '()
      :associative {}
      :string ""
      :unhandled 42
      :unhandled #{})))

;; TODO: `(deftest analyze-artifact

(deftest sort-key
  (testing "regular case"
    (are [x y] (= x (ukko/sort-key y))
      [nil :foo] [:foo {}]
      [nil :bar] [:bar {}]
      [42 :foobar] [:foobar {:priority 42}]))
  (testing "using it to sort"
    (is (= [[:c {:priority 1}]
            [:a {:priority 50}]
            [:b {:priority 50}]]
           (sort-by ukko/sort-key {:b {:priority 50}
                                   :a {:priority 50}
                                   :c {:priority 1}})))))

(deftest sanitize-id
  (testing "regular case"
    (is (= {:id "hello-world"}
           (ukko/sanitize-id {:id "Hello World"})))))

(deftest remove-fsdb-base
  (testing "regular case"
    (is (= 42
           (ukko/remove-fsdb-base "a/b" {:a {:b 42}})))))

(deftest add-data
  (testing "regular case"
    (let [ctx {:data-path "test/fixtures/data"}]
      (is (= (assoc ctx :data {:some {:sample "data"}})
             (ukko/add-data ctx))))))

;; TODO: `(deftest add-layouts

;; TODO: maybe `(deftest add-files

;; TODO: `(deftest add-artifacts

;; TODO: maybe `(deftest generate!

;; TODO: maybe `(deftest main
