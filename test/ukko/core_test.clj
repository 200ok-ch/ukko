(ns ukko.core-test
  (:require [clojure.test :refer :all]
            [ukko.core :as ukko]))

(deftest transform
  (testing "org"
    (is (= "<ul class=\"org-ul\">\n<li>Hello world</li>\n</ul>\n"
           (ukko/transform :org "- Hello world" {:cwd "/tmp"}))))
  (testing "markdown"
    (is (= "<h1><a href=\"#hello-world\" id=\"hello-world\">Hello world</a></h1>\n"
           (ukko/transform :md "# Hello world" {}))))
  (testing "fleet"
    (is (= "<h1>Hello world</h1>\n"
           (ukko/transform :fleet "<h1><(:title ctx)></h1>\n" {:title "Hello world"}))))
  (testing "fleet with i18n"
    (let [ctx {:i18n {:landing {:title "Welcome"}}}]
      (testing "with existing key"
        (is (= "<h1>Welcome</h1>\n"
               (ukko/transform :fleet "<h1>{{ i18n \"landing.title\" }}</h1>\n" 
                             ctx))))
      (testing "with missing key"
        (is (= "<h1>{{ i18n \"landing.missing\" }}</h1>\n"
               (ukko/transform :fleet "<h1>{{ i18n \"landing.missing\" }}</h1>\n"
                             ctx)))))))

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

(deftest artifact-locale
  (testing "explicit locale takes precedence"
    (let [artifact {:locale :en :canonical-link "/de/blog.html"}
          all-locales [:en :de :fr]
          default-locale :en]
      (is (= :en (ukko/artifact-locale artifact all-locales default-locale)))))
  
  (testing "infer locale from canonical link"
    (let [artifact {:canonical-link "/de/posts/sample.html"}
          all-locales [:en :de :fr]
          default-locale :en]
      (is (= :de (ukko/artifact-locale artifact all-locales default-locale))))
    
    (let [artifact {:canonical-link "/fr/tags/test.html"}
          all-locales [:en :de :fr]
          default-locale :en]
      (is (= :fr (ukko/artifact-locale artifact all-locales default-locale)))))
  
  (testing "fallback to default locale"
    (let [artifact {:canonical-link "/blog.html"}
          all-locales [:en :de :fr]
          default-locale :en]
      (is (= :en (ukko/artifact-locale artifact all-locales default-locale))))
    
    (let [artifact {}
          all-locales [:en :de :fr]
          default-locale :de]
      (is (= :de (ukko/artifact-locale artifact all-locales default-locale))))))

(deftest translated-url
  (testing "using translationKey"
    (let [ctx {:artifacts {"post1" {:translationKey "sample_post" :canonical-link "/de/posts/beispiel.html" :locale :de}
                           "post2" {:translationKey "other_post" :canonical-link "/de/posts/other.html" :locale :de}
                           "post3" {:translationKey "sample_post" :canonical-link "/fr/posts/exemple.html" :locale :fr}}
               :config {:i18n {:locales [:en :de :fr]
                               :default-locale :en}}}
          artifact {:translationKey "sample_post" :canonical-link "/en/posts/sample.html"}
          target-locale :de]
      (is (= "/de/posts/beispiel.html" 
             (ukko/translated-url ctx artifact target-locale)))))
  
  (testing "using translationKey with path-based locale inference"
    (let [ctx {:artifacts {"post1" {:translationKey "sample_post" :canonical-link "/de/posts/beispiel.html"}
                           "post2" {:translationKey "sample_post" :canonical-link "/fr/posts/exemple.html"}}
               :config {:i18n {:locales [:en :de :fr]
                               :default-locale :en}}}
          artifact {:translationKey "sample_post" :canonical-link "/en/posts/sample.html"}
          target-locale :de]
      (is (= "/de/posts/beispiel.html" 
             (ukko/translated-url ctx artifact target-locale)))))
  
  (testing "path rewriting when no translationKey"
    (let [ctx {:artifacts {}
               :config {:i18n {:locales [:en :de :fr]
                               :default-locale :en}}}
          artifact {:canonical-link "/en/tags/clojure.html"}
          target-locale :de]
      (is (= "/de/tags/clojure.html" 
             (ukko/translated-url ctx artifact target-locale))))
    
    (let [ctx {:artifacts {}
               :config {:i18n {:locales [:en :de :fr]
                               :default-locale :en}}}
          artifact {:canonical-link "/fr/blog.html"}
          target-locale :en]
      (is (= "/en/blog.html" 
             (ukko/translated-url ctx artifact target-locale)))))
  
  (testing "prefix path without locale"
    (let [ctx {:artifacts {}
               :config {:i18n {:locales [:en :de :fr]
                               :default-locale :en}}}
          artifact {:canonical-link "/blog.html"}
          target-locale :de]
      (is (= "/de/blog.html" 
             (ukko/translated-url ctx artifact target-locale)))))
  
  (testing "fallback to target locale root when no canonical-link"
    (let [ctx {:artifacts {}
               :config {:i18n {:locales [:en :de :fr]
                               :default-locale :en}}}
          artifact {}
          target-locale :fr]
      (is (= "/fr/" 
             (ukko/translated-url ctx artifact target-locale))))))

(deftest language-switcher-html
  (testing "basic language switcher with path rewriting"
    (let [ctx {:artifact {:canonical-link "/en/tags/clojure.html"}
               :config {:i18n {:locales [:en :de :fr]
                               :default-locale :en}}
               :artifacts {}}]
      (is (= "<a href='/de/tags/clojure.html'>DE</a> | <a href='/fr/tags/clojure.html'>FR</a>"
             (ukko/language-switcher-html ctx)))))
  
  (testing "language switcher with translationKey"
    (let [ctx {:artifact {:translationKey "sample_post" 
                          :canonical-link "/en/posts/sample.html"}
               :config {:i18n {:locales [:en :de]
                               :default-locale :en}}
               :artifacts {"post1" {:translationKey "sample_post" 
                                    :canonical-link "/de/posts/beispiel.html"
                                    :locale :de}}}]
      (is (= "<a href='/de/posts/beispiel.html'>DE</a>"
             (ukko/language-switcher-html ctx)))))
  
  (testing "language switcher from German page"
    (let [ctx {:artifact {:canonical-link "/de/blog.html"}
               :config {:i18n {:locales [:en :de :fr]
                               :default-locale :en}}
               :artifacts {}}]
      (is (= "<a href='/en/blog.html'>EN</a> | <a href='/fr/blog.html'>FR</a>"
             (ukko/language-switcher-html ctx)))))
  
  (testing "single language - no switcher"
    (let [ctx {:artifact {:canonical-link "/en/blog.html"}
               :config {:i18n {:locales [:en]
                               :default-locale :en}}
               :artifacts {}}]
      (is (= ""
             (ukko/language-switcher-html ctx)))))
  
  (testing "explicit locale takes precedence over path inference"
    (let [ctx {:artifact {:locale :fr 
                          :canonical-link "/en/blog.html"}
               :config {:i18n {:locales [:en :de :fr]
                               :default-locale :en}}
               :artifacts {}}]
      (is (= "<a href='/en/blog.html'>EN</a> | <a href='/de/blog.html'>DE</a>"
             (ukko/language-switcher-html ctx))))))
