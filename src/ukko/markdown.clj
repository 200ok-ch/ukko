(ns ukko.markdown
  (:require [clojure.java.io :as io])
  (:import [com.vladsch.flexmark.html HtmlRenderer]
           [com.vladsch.flexmark.ext.tables TablesExtension]
           [com.vladsch.flexmark.ext.gfm.strikethrough StrikethroughExtension]
           [com.vladsch.flexmark.ext.anchorlink AnchorLinkExtension]
           [com.vladsch.flexmark.ext.autolink AutolinkExtension]
           [com.vladsch.flexmark.util.data MutableDataSet]
           [com.vladsch.flexmark.parser Parser]
           [java.util Arrays]))

;; Relevant links:
;;;  Extensions: https://github.com/vsch/flexmark-java/wiki/Extensions
;;;  Usage: https://github.com/vsch/flexmark-java/wiki/Usage

(defn to-html [md-text]
  (let [extensions (Arrays/asList
                    (into-array Object
                                [; XXX: autolink has significant
                                 ; performance impact on large files
                                 (AutolinkExtension/create)
                                 (StrikethroughExtension/create)
                                 (AnchorLinkExtension/create)
                                 (TablesExtension/create)]))
        options    (doto (MutableDataSet.)
                     (.set Parser/EXTENSIONS extensions)
                     (.set HtmlRenderer/GENERATE_HEADER_ID true))
        builder (Parser/builder options)
        parser (.build  builder)
        renderer (.build (HtmlRenderer/builder options))]
    (->> md-text
         (.parse parser)
         (.render renderer))))
