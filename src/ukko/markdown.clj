;; Based upon https://github.com/hashobject/perun/blob/master/src/io/perun/markdown.clj
(ns ukko.markdown
  (:require [clojure.java.io :as io])
  (:import [com.vladsch.flexmark Extension]
           [com.vladsch.flexmark.html HtmlRenderer]
           [com.vladsch.flexmark.parser Parser]
           [com.vladsch.flexmark.profiles.pegdown Extensions PegdownOptionsAdapter]))

(def extensions
  {:smarts               Extensions/SMARTS
   :quotes               Extensions/QUOTES
   :smartypants          Extensions/SMARTYPANTS
   :abbreviations        Extensions/ABBREVIATIONS
   :hardwraps            Extensions/HARDWRAPS
   :autolinks            Extensions/AUTOLINKS
   :tables               Extensions/TABLES
   :definitions          Extensions/DEFINITIONS
   :fenced-code-blocks   Extensions/FENCED_CODE_BLOCKS
   :wikilinks            Extensions/WIKILINKS
   :strikethrough        Extensions/STRIKETHROUGH
   :anchorlinks          Extensions/ANCHORLINKS
   :all                  Extensions/ALL
   :atxheaderspace       Extensions/ATXHEADERSPACE
   :forcelistitempara    Extensions/FORCELISTITEMPARA
   :relaxedhrules        Extensions/RELAXEDHRULES
   :tasklistitems        Extensions/TASKLISTITEMS
   :extanchorlinks       Extensions/EXTANCHORLINKS
   :all-optionals        Extensions/ALL_OPTIONALS
   :all-with-optionals   Extensions/ALL_WITH_OPTIONALS
   :footnotes            Extensions/FOOTNOTES})

(defn extensions-map->int [opts]
  (->> opts
       (merge {:autolinks true
               :strikethrough true
               :fenced-code-blocks true
               :extanchorlinks true})
       (filter val)
       keys
       (map extensions)
       (apply bit-or 0)
       int))

(defn to-html [md-text]
  (let [flexmark-opts (PegdownOptionsAdapter/flexmarkOptions
                       (extensions-map->int extensions)
                       (into-array Extension []))
        builder (Parser/builder flexmark-opts)
        builder (.set builder Parser/LISTS_ITEM_INDENT (int 2))
        ;; builder (.set builder Parser/WWW_AUTO_LINK_ELEMENT true)
        parser (.build  builder)
        renderer (.build (HtmlRenderer/builder flexmark-opts))]
    (->> md-text
         (.parse parser)
         (.render renderer))))
