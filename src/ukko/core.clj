(ns ukko.core
  (:gen-class)
  (:require [org.httpkit.server :as server]
            [hawk.core :as hawk]
            [clojure.tools.cli :refer [parse-opts]]
            [yaml.core :as yaml]
            [compojure.route :as route]
            [compojure.core :refer [defroutes]]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.term.colors :as color]
            [fsdb.core :as fsdb]
            [fleet :refer [fleet]]
            markdown.core)
  (:import [java.util Timer TimerTask]))

(def cli-options
  [["-l" "--linkcheck" "After generating the site check links"]
   ["-p" "--port PORT" "Port for http server" :default 8080]
   ["-s" "--server" "Run a http server"]
   ["-c" "--continous" "Regenerate site on file change"]
   ["-v" "--verbose" "Verbose output"]
   ["-q" "--quiet" "Suppress output"]])

;; FIXME: this seems unreliabl, use a version built on core.async instead
;; TODO: move to singlemalt.java
(defn debounce
  ([f] (debounce f 1000))
  ([f timeout]
   (let [timer (Timer.)
         task (atom nil)]
     (with-meta
       (fn [& args]
         (when-let [t ^TimerTask @task]
           (.cancel t))
         (let [new-task (proxy [TimerTask] []
                          (run []
                            (apply f args)
                            (reset! task nil)
                            (.purge timer)))]
           (reset! task new-task)
           (.schedule timer new-task timeout)))
       {:task-atom task}))))

;; (defn progress [color & args]
;;   (if (fn? color)
;;     (let [[prefix & msg] args]
;;       (println (color prefix) (str/join " " msg))
;;       (println (color/blue color) (str/join	" " args)))))

(defn- pandoc [f t]
  (:out (shell/sh "pandoc" "-f" f "-t" "html" "-" :in t)))

(defmulti transform (fn [f _ _] f))

(defmethod transform :passthrough [_ template _]
  template)

(defmethod transform :org [_ template _]
  (pandoc "org" template))

(defmethod transform :md [_ template _]
  (pandoc "markdown" template))

(defmethod transform :fleet [_ template ctx]
  (.toString ((fleet [ctx] template) ctx)))

(defmethod transform :scss [_ template {:keys [cwd]}]
  (let [{:keys [err out]} (shell/sh "sass" "--stdin" "-I" cwd :in template)]
    (when err
      (println (color/red err)))
    out))

;; --------------------------------------------------------------------------------

(def mmap map)

(defonce server (atom nil))

(defn stop-server []
  (when-not (nil? @server)
    (@server :timeout 100)
    (reset! server nil)))

(defroutes routes
  (route/files "/")
  (route/not-found "<p>Page not found.</p>"))

(defn start-server [port]
  (println (color/green "Server running... (terminate with Ctrl-c)"))
  (println (color/green "Visit") (str "http://localhost:" port))
  (reset! server (server/run-server routes {:port port
                                            :event-logger println})))

;; --------------------------------------------------------------------------------

(def defaults
  {:assets-path "assets"
   :data-path "data"
   :date-format-rfc-3339 "yyyy-MM-dd'T'HH:mm:ss'Z'"
   :date-format-rfc-822 "EEE, dd MMM yyyy HH:mm:ss Z"
   :site-path "site"
   :layouts-path "layouts"
   :target-path "public"
   :target-extension ".html"
   :format "passthrough"
   :layout ["post" "blog"]
   :priority 50})

(defn config []
  ;; FIXME: should be a deep merge, use the one from singlemalt
  (merge defaults
         (when (.exists (io/file "ukko.yml"))
           (-> "ukko.yml"
               slurp
               yaml/parse-string))))

(defn find-files [path]
  (println (color/blue "Finding files in") path)
  (->> path
       clojure.java.io/file
       file-seq
       (filter #(.isFile %))
       (mapv #(.getPath %))))

(defn parse-file
  "If the file has a YAML frontmatter it returns the frontmatter (as
  map) with `:path` and `:template` assoc'ed. If the file does not
  have a YAML frontmatter (or fails to parse it) it returns a map with
  `:format` and `:path`. The value of `:format` will then be `:copy`."
  [path]
  (println (color/blue "Reading file") path)
  ;; (print ".")
  (let [[frontmatter & contents]
        (-> path
            slurp
            (str/split #"\n---\n"))]
    (try
      (if (nil? contents)
        (throw "no template")
        (-> frontmatter
            yaml/parse-string
            (assoc :path path
                   :template (str/join "\n---\n" contents))))
      (catch Exception e
        {:format "copy"
         :path path}))))

;; (defn process [format {:keys [path target-path template content scope] :as artifact} ctx]
;;   (case format
;;     :copy (shell/sh "rsync" "-a" path target-path)
;;     (let [ctx (-> (merge ctx artifact)
;;                   (assoc :cwd (str/replace path #"/[^/]+$" "")))]
;;       (->> (str "[" scope "]")
;;            read-string
;;            (get-in ctx)
;;            (transform format (or content template))
;;            (assoc artifact :content)))))

(defmulti process (fn [format & _] format))

(defmethod process :default [format {:keys [path template] :as artifact} _]
  (println (color/red "Unknown format") (name format) (color/red "for file") path)
  (assoc artifact :content template))

(defmethod process :copy [_ {:keys [path target-path] :as artifact} _]
  (shell/sh "rsync" "-a" path target-path)
  artifact)

(defmethod process :passthrough [_ {:keys [template] :as artifact} _]
  (assoc artifact :contents template))

(defmethod process :md [_ {:keys [path] :as artifact} _]
  (assoc artifact :content (:out (shell/sh "pandoc" "-f" "markdown" "-t" "html" path))))

(defmethod process :org [_ {:keys [template] :as artifact} _]
  (assoc artifact :content (:out (shell/sh "pandoc" "-f" "org" "-t" "html" :in template))))

(defmethod process :scss [_ {:keys [path template target-path] :as artifact} _]
  (let [directory (str/replace path #"/[^/]+$" "")
        {:keys [err out]} (shell/sh "sass" "--stdin" "-I" directory :in template)]
    (when err
      (println (color/red err)))
    (assoc artifact :content out)))

(defmethod process :fleet [_ {:keys [scope template] :as artifact} ctx]
  (let [scoped (get-in (merge ctx artifact) (read-string (str "[" scope "]")))
        result (transform :fleet template scoped)]
    (assoc artifact :content result)))

(defn add-defaults [config artifact]
  ;; FIXME: should be a deep merge, use the one from singlemalt
  (merge config artifact))

(defn make-id [{:keys [path]} base]
  (-> path
      (str/replace #"\.[^.]+$" "")
      (str/replace (str base "/") "")))

(defn apply-layout [{:keys [layouts] :as ctx} artifact content layout-id]
  (if-let [{:keys [format template]} (get layouts layout-id)]
    ;; NOTE: layouts can only handle one format
    (transform (keyword format) template
               (assoc ctx :artifact artifact :content content))
    content))

(defn apply-layouts [ctx {:keys [layout content] :as artifact}]
  (->> layout
       vector
       flatten
       (reduce (partial apply-layout ctx artifact) content)
       (assoc artifact :output)))

(defn add-target [{:keys [target-path id target-extension] :as artifact}]
  (assoc artifact :target (str target-path "/" id target-extension)))

(defn add-id [path artifact]
  (assoc artifact :id (make-id artifact path)))

(defn add-canonical-link [{:keys [id target-extension] :as artifact}]
  (assoc artifact :canonical-link (str "/" id target-extension)))

(defn add-word-count [{:keys [template] :as artifact}]
  ;; TODO: dont use template for this but the result after
  ;; transforming it to text-only
  (if template
    (assoc artifact :word-count (count (str/split template #"\s+")))
    artifact))

(defn add-ttr [{:keys [word-count] :as artifact}]
  (if word-count
    (assoc artifact :ttr (-> word-count (/ 180) Math/ceil int))
    artifact))

(defn add-date-published-rfc-3339 [{:keys [date-published] :as artifact}]
  (if date-published
    (assoc artifact :date-published-rfc-3339 (.format (java.text.SimpleDateFormat. (:date-format-rfc-3339 artifact)) date-published))
    artifact))

(defn add-date-published-rfc-822 [{:keys [date-published] :as artifact}]
  (if date-published
    (assoc artifact :date-published-rfc-822 (.format (java.text.SimpleDateFormat. (:date-format-rfc-822 artifact)) date-published))
    artifact))

(defn fix-format [field artifact]
  (if (field artifact)
    (update artifact field #(.format (java.text.SimpleDateFormat. "yyyy-MM-dd") %))
    artifact))

(defn add-text [{:keys [content] :as artifact}]
  (if content
    (assoc artifact :text (str/replace content #"</?[^<>]+>" ""))
    artifact))

(defn add-preview [{:keys [text] :as artifact}]
  (if text
    (->> (str/split text #"\s+")
         (take 100)
         (str/join " ")
         (assoc artifact :preview))
    artifact))

(defn process-artifact
  "Returns the CTX with the artifact referenced by ARTIFACT-ID fully
  processed. Adds `:contents` to the artifact."
  [ctx {:keys [path format priority] :as artifact}]
  (println (color/blue "Processing artifact") (:id artifact) format priority)
  ;; (print ".")
  (->> format
       vector
       flatten
       (reduce #(process (keyword %2) %1 ctx) artifact)
       (apply-layouts ctx)
       add-text
       add-preview))

(defn process-artifact-id
  [{:keys [data artifacts] :as ctx} artifact-id]
  (->> (get artifacts artifact-id)
       (process-artifact ctx)
       (assoc-in ctx [:artifacts artifact-id])))

;; TODO: move to singlemalt
(defn cartesian-product [colls]
  (if (empty? colls)
    '(())
    (for [more (cartesian-product (rest colls))
          x (first colls)]
      (cons x more))))

(defn modify-id [id fragment]
  (str/replace id #"[^/]+$" (str fragment)))

(declare ^:dynamic ctx)

(defn collection-type [collection]
  (cond
    (nil? collection) :nil
    (sequential? collection) :sequential
    (associative? collection) :associative
    (string? collection) :string
    :else :unhandled))

(defmulti analyze-artifact (fn [_ {:keys [collection]}]
                             (collection-type collection)))

(defmethod analyze-artifact :nil [_ artifact]
  (->> artifact
       add-canonical-link
       add-word-count
       add-ttr
       add-date-published-rfc-3339
       add-date-published-rfc-822
       (fix-format :date-published)
       vector))

(defmethod analyze-artifact :sequential [context {:keys [id collection] :as artifact}]
  (->> (str "[" (str/join " " collection) "]")
       read-string
       (get-in context)
       (reduce-kv #(conj %1 (assoc %3 :id (modify-id id (name %2)))) [])
       (map (partial merge artifact))))

(defmethod analyze-artifact :associative [context {:keys [id collection] :as artifact}]
  (->> collection
       (reduce-kv #(assoc %1 %2 (get-in context (read-string (str "[" %3 "]")))) {})
       (reduce-kv #(assoc %1 %2 (reduce-kv (fn [a b c] (conj a (assoc c :id (name b)))) [] %3)) {})
       vals
       cartesian-product
       (map (partial interleave (keys collection)))
       (map (partial apply hash-map))
       (map #(assoc % :id (modify-id id (str/join "-" (map :id (vals %))))))
       (map (partial merge artifact))))

(defmethod analyze-artifact :string [context {:keys [id collection] :as artifact}]
  (binding [*ns* (find-ns 'ukko.core)
            ctx context]
    (->> (load-string collection)
         (map #(assoc % :id (modify-id id (:id %))))
         (map (partial merge artifact)))))

(def sort-key
  (juxt (comp :priority last) first))

(defn sanitize-id [artifact]
  (println "Sanitize" (:id artifact))
  (update artifact :id (comp #(str/replace % #" " "-") str/lower-case)))

(defn add-data [{:keys [data-path] :as ctx}]
  (->> data-path
       fsdb/read-tree
       (assoc ctx :data)))

(defn add-layouts [{:keys [layouts-path] :as ctx}]
  (->> layouts-path
       find-files
       (mmap parse-file)
       (mmap (partial add-id layouts-path))
       (reduce #(assoc %1 (:id %2) %2) {})
       (assoc ctx :layouts)))

(defn add-files [{:keys [site-path] :as ctx}]
  (->> site-path
       find-files
       (assoc ctx :artifact-files)))

;; TODO: refactor this
(defn add-artifacts [{:keys [artifact-files site-path config] :as ctx}]
  (let [artifacts (mmap parse-file artifact-files) ;; add-artifacts
        artifacts (mmap (partial add-defaults config) artifacts)
        artifacts (mmap (partial add-id site-path) artifacts)
        ctx (assoc ctx :artifacts artifacts)
        artifacts (apply concat (mmap (partial analyze-artifact ctx) artifacts))
        artifacts (mmap sanitize-id artifacts)
        artifacts (mmap add-target artifacts)
        artifacts-map (reduce #(assoc %1 (:id %2) %2) {} artifacts)
        ctx (assoc ctx :artifacts artifacts-map)
        artifact-ids (->> ctx :artifacts (sort-by sort-key) (map first))
        ctx (reduce process-artifact-id ctx artifact-ids)]
    ctx))

(defn generate! [options]
  ;; TODO: measure time and display result on complete
  (println (color/blue "Reading config..."))
  (let [{:keys [data-path assets-path target-path site-path layouts-path] :as config} (config)]
    (println (color/blue "Syncing assets..."))
    (shell/sh "rsync" "-a" (str assets-path "/") target-path)
    (println (color/blue "Generating site..."))
    (let [ctx (-> config
                  (assoc :config config)
                  add-data
                  add-layouts
                  add-files
                  add-artifacts)
          artifacts (->> ctx :artifacts vals (sort-by :id))]
      (doall
       (for [{:keys [id output hidden target] :as artifact} artifacts]
         (if hidden
           (println (color/yellow "Skipping hidden artifact") id)
           (do
             ;; (print ".")
             (println (color/blue "Writing") target (str "(" (count output) " bytes)"))
             ;; TODO: measure write time
             (io/make-parents target)
             (spit target output)))))
      (println (color/green (str "Complete. Wrote " (count artifacts) " artifacts.")))
      (when (:linkcheck options)
        (println (color/blue "Checking links... (this might take a while)"))
        (let [{:keys [out]} (shell/sh "linkchecker" "-o" "html" target-path)
              filename "/linkchecker.html"
              target (str target-path filename)
              url (str "http://localhost:" (:port options) filename)]
          (println (color/blue "Writing file:") target)
          (spit target out)
          (if (:server options)
            (println (color/green "For a report visit") url)))))))

(defn -main [& args]
  (let [{:keys [options errors]} (parse-opts args cli-options)]
    (if (:continous options)
      (let [paths [(:site-path (config))
                   (:layouts-path (config))
                   (:assets-path (config))
                   (:data-path (config))]]
        (println (color/blue "Watching files..."))
        (doall
         (for [path paths]
           (println "->" path)))
        (hawk/watch!
         [{:paths paths
           ;; TODO: debounce events
           :handler (debounce (fn [ctx {:keys [kind file]}]
                                ;; (println kind "=>" (.getPath file))
                                (generate! options)))}])))
    ;; initial
    (generate! options)
    (if (:server options)
      (start-server (:port options)))
    ;; (println "Starting REPL...")
    ;; (clojure.main/repl :init #(in-ns 'ch.200ok))
    ;; (println "\nTerminating... (Force with [Ctrl-c])")
    ;;(stop-server)
    ;; linkchecker public
    (if-not (or (:continous options)
                (:server options))
      (System/exit 0))))
