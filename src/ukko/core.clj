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
            [ukko.markdown :as markdown]
            [etaoin.api :as webdriver]
            [me.raynes.fs :as fs]
            [clojure.edn :as edn])
  (:import [java.util Timer TimerTask]
           [java.io File]))

(defonce driver (atom nil))
(defonce workdir (atom "."))

(defn md-to-html [md]
  (if md (markdown/to-html md)))

(def cli-options
  [["-l" "--linkcheck" "After generating the site check links"]
   ["-p" "--port PORT" "Port for http server" :default 8080]
   ["-s" "--server" "Run a http server"]
   ["-c" "--continuous" "Regenerate site on file change"]
   ["-f" "--filter FILTER" "Generate only files matching the regex FILTER"]
   ["-v" "--verbose" "Verbose output"]
   ["-b" "--browser BROWSER" "Start a browser with live-reload (either firefox, chrome, or safari)"]
   ["-d" "--directory DIR" "Use DIR as the site directory" :default "."]
   ["-q" "--quiet" "Suppress output"]])

;; FIXME: this seems unreliable, use a version built on core.async
;; instead
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

;; TODO: provide a real logging facility that can handle colors &
;; logging levels
;; (defn log [color & args]
;;   (if (fn? color)
;;     (let [[prefix & msg] args]
;;       (println (color prefix) (str/join " " msg))
;;       (println (color/blue color) (str/join	" " args)))))

(defn- pandoc [f t]
  (:out (shell/sh "pandoc" "-f" f "-t" "html" "-" :in t)))

(defmulti transform (fn [f _ _] f))

(defmethod transform :default [f template _]
  (pandoc (name f) template))

(defmethod transform :passthrough [_ template _]
  template)

(defmethod transform :org [_ template {:keys [cwd]}]
  (let [input-file (.getAbsolutePath (File/createTempFile "ukko" ".org" (new File cwd)))
        output-file (str/replace input-file ".org" ".html")
        elisp (str "(progn (find-file \"" input-file "\") (org-html-export-to-html nil nil t t nil) (kill-this-buffer))")
        _ (spit input-file template)
        res (shell/sh "emacsclient" "-a" "" "-e" elisp)]
    (when (and (:out res) (not-empty (:out res)) (not= (str/trim (:out res)) "nil"))
      (println (:out res)))
    (when (not-empty (:err res))
      (println (color/red "Error from org export:") (:err res)))
    (let [output (slurp output-file)]
      (clojure.java.io/delete-file output-file)
      (clojure.java.io/delete-file input-file)
      output)))

(defmethod transform :md [_ template _]
  (md-to-html template))

(defn- process-i18n [template ctx]
  (let [i18n-pattern #"\{\{\s*i18n\s*\"([^\"]+)\"\s*\}\}"
        i18n-data (get ctx :i18n)]
    (if i18n-data
      (str/replace template i18n-pattern
                   (fn [[match key]]
                     (or (get-in i18n-data (map keyword (str/split key #"\.")))
                         match)))
      template)))

(defn i18n
  "Function to access i18n data from Fleet templates.
   Usage: <(i18n \"landing.welcome\")>"
  [key ctx]
  (let [i18n-data (get ctx :i18n)]
    (if i18n-data
      (or (get-in i18n-data (map keyword (str/split key #"\.")))
          key)
      key)))

(defmethod transform :fleet [_ template ctx]
  (let [processed-template (process-i18n template ctx)
        ctx-with-i18n-fn (assoc ctx :i18n (partial i18n ctx))]
    (.toString ((fleet [ctx] processed-template) ctx-with-i18n-fn))))

(defmethod transform :scss [_ template {:keys [cwd]}]
  (let [{:keys [err out]} (shell/sh "sassc" "--stdin" "-I" cwd :in template)]
    (when err
      (println (color/red err)))
    out))

;; TODO: move to singlemalt
(def ^:private vecflat (comp flatten vector))

(defn- render-title [{:keys [title] :as artifact}]
  (if title
    ;; run only through fleet no matter what
    (update artifact :title #(transform :fleet % artifact))
    artifact))

;; --------------------------------------------------------------------------------

(def mmap pmap)

(defonce server (atom nil))

(defn stop-server []
  (when-not (nil? @server)
    (@server :timeout 100)
    (reset! server nil)))

(defn make-routes [target-path]
  (compojure.core/routes
    (route/files "/" {:root target-path})
    (route/not-found "<p>Page not found.</p>")))

(defn start-server [port target-path]
  (println (color/green "Server running... (terminate with Ctrl-c)"))
  (println (color/green "Visit") (str "http://localhost:" port))
  (println (color/green "Serving files from") target-path)
  (reset! server (server/run-server (make-routes target-path) {:port port
                                                               :event-logger println})))

;; --------------------------------------------------------------------------------

(def date-format-rfc-3339
  "A random date format good for Atom feeds."
  "yyyy-MM-dd'T'HH:mm:ss'Z'")

(def date-format-rfc-822
  "A random date format good for RSS feeds."
  "EEE, dd MMM yyyy HH:mm:ss Z")

(def date-format-iso
  "A date format good for sitemaps."
  "yyyy-MM-dd")

(defn format-date
  "Formats a give DATE according to FORMAT. Defaults to now if DATE is
  omitted."
  ([format] (format-date format (java.util.Date.)))
  ([format date]
   (.format (java.text.SimpleDateFormat. format) date)))

(def defaults
  "This holds 'global' settings as well as defaults for artifacts. This
  will be the merge base for each artifact. So all of this is directly
  available on each artifact, unless overwritten. (Be wary not to
  overwrite any of these by accident!)"
  {:assets-path "assets"
   :data-path "data"
   :site-path "site"
   :layouts-path "layouts"
   :target-path "public"
   :target-extension ".html"
   :format "passthrough"
   :layout ["post" "blog"]
   :ignore-file-patterns ["^\\."]
   :priority 50
   :now-rfc-3339 (format-date date-format-rfc-3339)
   :now-rfc-822 (format-date date-format-rfc-822)})

(defn path-join [p & ps]
  (str (.normalize (java.nio.file.Paths/get p (into-array String ps)))))

(defn config []
  ;; FIXME: should be a deep merge, use the one from singlemalt
  (let [config-file (path-join @workdir "ukko.yml")]
    (merge defaults
           (when (.exists (io/file config-file))
             (-> config-file
                 slurp
                 yaml/parse-string))
           {:assets-path (path-join @workdir "assets")
            :data-path (path-join @workdir "data")
            :site-path (path-join @workdir "site")
            :layouts-path (path-join @workdir "layouts")
            :target-path (path-join @workdir "public")})))

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
    (if (nil? contents)
      (println (color/yellow "Skipping file without frontmatter:") path)
      (try
        (-> frontmatter
            yaml/parse-string
            (assoc :path path
                   :mtime (->> path
                               java.io.File.
                               .lastModified
                               java.util.Date.
                               (format-date date-format-iso))
                   :template (str/join "\n---\n" contents)))
        (catch Exception e
          ;; TODO: stop here if a malformed YAML is encountered
          (println (color/red "Malformed YAML:") (.getMessage e)))))))

(defn process [format {:keys [path target-path template content scope] :as artifact} ctx]
  (let [ctx (-> (merge ctx artifact)
                (assoc :cwd (str/replace path #"/[^/]+$" "")))]
    ;; (println (color/magenta "Add content to") (:id artifact))
    (->> (str "[" scope "]")
         read-string
         (get-in ctx)
         (transform format (or content template))
         (assoc artifact :content))))

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
  ;; (println (color/magenta "Add canonical-link") (str "/" id target-extension))
  (assoc artifact :canonical-link (-> (str "/" id target-extension)
                                      str/lower-case)))

(defn add-canonical-category [{:keys [category] :as artifact}]
  ;; (println (color/magenta "Add canonical-link") (str "/" id target-extension))
  (if category
    (assoc artifact :canonical-category (-> category clojure.string/lower-case (#(clojure.string/replace % #"\ " "-"))))
    artifact))

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
    (assoc artifact :date-published-rfc-3339 (format-date date-format-rfc-3339 date-published))
    artifact))

(defn add-date-published-rfc-822 [{:keys [date-published] :as artifact}]
  (if date-published
    (assoc artifact :date-published-rfc-822 (format-date date-format-rfc-822 date-published))
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
  (->> format
       vecflat
       (map keyword)
       (reduce #(process %2 %1 ctx) artifact)
       (apply-layouts ctx)
       add-text
       add-preview))

(defn process-artifact-id
  "Render artifact by running the template through transformations (as
  given by `:format`).

  Adds `:content` (rendered html), `:text` (same as content but
  without tags), `:preview` (same as text but only the first 100
  words)."
  [{:keys [data artifacts] :as ctx} artifact-id]
  ;; TODO: probably the same as
  ;; (update-in ctx [:artifacts artifact-id] (partial process-artifact ctx))
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
    (let [result (->> (load-string collection)
                      (map #(assoc % :id (modify-id id (:id %))))
                      (map (partial merge artifact)))]
      ;; debug output for collections
      ;; (let [path (str "public/" id ".edn")]
      ;;   (io/make-parents path)
      ;;   (spit path (with-out-str (clojure.pprint/pprint result))))
      result)))

(def sort-key
  (juxt (comp :priority last) first))

(defn sanitize-id [artifact]
  (update artifact :id (comp #(str/replace % #" " "-") str/lower-case str)))

(defn remove-fsdb-base [path data]
  (->> (str/split path #"/")
       (map keyword)
       (reduce get data)))

(defn add-data [{:keys [data-path] :as ctx}]
  (->> data-path
       fsdb/read-tree
       (remove-fsdb-base data-path)
       (assoc ctx :data)))

(defn add-layouts [{:keys [layouts-path] :as ctx}]
  (->> layouts-path
       find-files
       (mmap parse-file)
       (remove nil?)
       (mmap (partial add-id layouts-path))
       (reduce #(assoc %1 (:id %2) %2) {})
       (assoc ctx :layouts)))

(defn make-tmp-dir!
  ([] (make-tmp-dir! ""))
  ([prefix] (make-tmp-dir! prefix ""))
  ([prefix suffix] (make-tmp-dir! prefix suffix (System/getProperty "java.io.tmpdir")))
  ([prefix suffix path]
   (let [random (str (System/currentTimeMillis) "-" (long (rand 1000000000)))
         path (path-join path (str prefix random suffix))]
     (println (color/blue "Make temp directory") path)
     (io/make-parents path)
     (.mkdir (File. path))
     path)))

(defn make-workdir [{:keys [site-path] :as ctx}]
  (let [workdir (make-tmp-dir!)]
    (println (color/blue "Copy site files to") workdir)
    (fs/copy-dir-into site-path workdir)
    (assoc ctx :workdir workdir)))

(defn add-files [{:keys [workdir] :as ctx}]
  (->> workdir
       find-files
       (assoc ctx :artifact-files)))

(defn filter-files [ctx options]
  (if (:filter options)
    (update ctx :artifact-files (partial filter (fn [file]
                                                  (re-find (re-pattern (:filter options)) file))))
    ctx))

(defn load-i18n [workdir locale]
  (let [i18n-path (str workdir "/i18n/" (name locale) ".yml")]
    (when (.exists (io/file i18n-path))
      (yaml/parse-string (slurp i18n-path)))))

(defn expand-i18n-artifact [artifact ctx workdir locales default-locale]
  (let [id (:id artifact)
        base-id (if (str/ends-with? id "/index")
                  (subs id 0 (- (count id) 6))
                  id)]
    (mapcat (fn [locale]
              (let [i18n-data (load-i18n workdir locale)
                    locale-id (if (= locale default-locale)
                                base-id
                                (str locale "/" base-id))
                    base-artifact (-> artifact
                                      (assoc :id locale-id
                                             :i18n i18n-data
                                             :locale locale))
                    ;; For default locale, output both /index.html and /en/index.html
                    default-artifacts (when (= locale default-locale)
                                        [(assoc base-artifact :id base-id)
                                         (assoc base-artifact :id (str (name locale) "/" base-id))])]
                (if (= locale default-locale)
                  default-artifacts
                  [(assoc base-artifact :id (str (name locale) "/" base-id))])))
            locales)
    ))

(defn add-artifacts [{:keys [artifact-files workdir config] :as ctx}]
  (let [artifacts (mmap parse-file artifact-files)                  ;; parse artifact files
        _ (println (color/green (str "Parsed " (count artifacts) " files")))
        artifacts (remove :hide artifacts)                          ;; remove hidden artifacts
        artifacts (remove nil? artifacts)                           ;; remove nil artifacts
        artifacts (mmap (partial add-defaults config) artifacts)    ;; merge each artifact into config (to set defaults)
        artifacts (mmap (partial add-id workdir) artifacts)         ;; add an `:id` to all artifacts (based on path, incl. filename)
        artifacts (mmap add-canonical-link artifacts)               ;; add `:canonical-link` to all artifacts (pre-explode)
        artifacts (mmap add-canonical-category artifacts)           ;; add `:canonical-category` to all artifacts (pre-explode)
        ;; i18n expansion step - with safe handling for missing i18n config
        i18n-cfg (:i18n config)
        locales (when i18n-cfg
                  (map name (or (:locales i18n-cfg) [(:default-locale i18n-cfg)])))
        default-locale (when i18n-cfg
                         (name (:default-locale i18n-cfg)))
        artifacts (if i18n-cfg
                    (mapcat (fn [artifact]
                              (if (= (get artifact :i18n) "generate-for-all-locales")
                                (expand-i18n-artifact artifact ctx workdir locales default-locale)
                                [artifact]))
                            artifacts)
                    artifacts)
        _ (println (color/green (str "Processing " (count artifacts) " artifacts")))
        ctx (assoc ctx :artifacts artifacts)                        ;; add `:artifacts` to `ctx`
        artifacts (apply concat (mmap (partial analyze-artifact ctx) artifacts)) ;; explode `:artifacts` that use collections into multiple artifacts, and join them back to a flat list of artifacts
        _ (println (color/green (str "Processing " (count artifacts) " artifacts")))
        artifacts (mmap add-canonical-link artifacts)               ;; add `:canonical-link` to all artifacts (post-explode)
        artifacts (mmap sanitize-id artifacts)                      ;; sanitize `:id` of all artifacts
        artifacts (mmap add-target artifacts)                       ;; add `:target` based on `:id` to all artifacts
        artifacts (mmap render-title artifacts)                     ;; uses the content of `:title` to render it
        artifacts-map (reduce #(assoc %1 (:id %2) %2) {} artifacts) ;; transform `:artifacts` into a map with `:id` as key
        ctx (assoc ctx :artifacts artifacts-map)                    ;; overwrite `:artifacts` with `artifacts-map`
        artifact-ids (->> ctx :artifacts (sort-by sort-key) (map first)) ;; get `artifact-ids` in order of `:priority` and `:id`

        ;; PARALLEL PROCESSING
        _ (println (color/blue "Processing artifacts in parallel..."))
        processed-artifacts (pmap (fn [artifact-id]
                                   (let [artifact (get (:artifacts ctx) artifact-id)
                                         processed-artifact (process-artifact ctx artifact)]
                                     [artifact-id processed-artifact]))
                                 artifact-ids)

        ;; Merge results back into context
        final-artifacts-map (reduce (fn [acc [artifact-id processed-artifact]]
                                     (assoc acc artifact-id processed-artifact))
                                   (:artifacts ctx)
                                   processed-artifacts)
        ctx (assoc ctx :artifacts final-artifacts-map)]           ;; update context with processed artifacts
    ctx))

(defn sync-assets! [{:keys [assets-path target-path]}]
  (println (color/blue "Syncing assets..."))
  (shell/sh "rsync" "-a" (str assets-path "/") target-path))

(defn generate! [options]
  ;; TODO: measure time and display result on complete
  (println (color/blue "Reading config..."))
  (let [{:keys [data-path target-path layouts-path] :as config} (config)]
    (println (color/blue "Generating site..."))
    (let [ctx (-> config                 ;; start with config
                  (assoc :config config) ;; save original config in `:config`
                  add-data               ;; adds fsdb data under `:data`
                  add-layouts            ;; add layouts map under `:layouts`
                  make-workdir           ;; creats a work dir and copies site files to it
                  add-files              ;; find files in workdir and under `:artifact-files`
                  (filter-files options) ;; limit files in `:artifact-files` according to `:filter` options
                  add-artifacts)         ;; "add" artifacts (see `add-artifacts`)
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
        (let [{:keys [out exit]} (apply shell/sh (flatten ["linkchecker" "--no-status" (str/split (or (:linkcheck-params config) "") #"\s+") target-path]))]
          (when (pos? exit)
            (println out)
            (println (color/red "Linkchecker found problems. Exiting with code") exit)
            (System/exit exit))))
      (println (color/blue "Cleanup."))
      (fs/delete-dir (:workdir ctx)))))

(defn hawk-handler [handler]
  (fn [ctx {:keys [kind file]}]
    (let [filename (.getName file)]
      ;; (println kind "=>" filename)
      (when-not (some identity
                      (map #(re-find (re-pattern %) filename)
                           ;; use config
                           (:ignore-file-patterns defaults)))
        ((debounce #(do
                      (handler filename)
                      (when @driver
                        ;; INFO: Not using (webdriver/refresh
                        ;; @driver), because this will do a hard
                        ;; refresh of the page which will result in a
                        ;; browser window that is scrolled to the top.
                        ;; When using the js code below, the browser
                        ;; does reload, but remains in the position
                        ;; the user was looking at.
                        (webdriver/js-execute @driver "window.location.reload()")))))))))

(defn -main [& args]
  (let [{:keys [options errors]} (parse-opts args cli-options)
        ;; When browser option is set, implicitly enable server option
        options (if (:browser options)
                  (assoc options :server true)
                  options)]
    (reset! workdir (:directory options))
    (let [{:keys [site-path layouts-path assets-path data-path] :as config¹} (config)]
      ;; continuous
      (if (:continuous options)
        (let [paths [site-path layouts-path assets-path data-path]]
          (println (color/blue "Watching files..."))
          (doall
           (for [path paths]
             (println "->" path)))
          (hawk/watch!
           [{:paths [data-path layouts-path]
             ;; TODO: someday/maybe find a way to track dependencies
             ;; from pages to data to generated filtered
             :handler (hawk-handler (fn [_] (generate! options)))}
            {:paths [site-path]
             ;; FIXME: in this case the debounce should be per filename,
             ;; or even better just maintain a register of dirty files
             ;; and regenerate all of the once the debounced handler is
             ;; fired
             :handler (hawk-handler #(->> % (assoc options :filter) generate!))}
            {:paths [assets-path]
             ;; TODO: someday/maybe only sync the affected file
             :handler (hawk-handler (fn [_] (sync-assets! config¹)))}])))
      ;; initial build
      (sync-assets! (config))
      (generate! options)
      ;; server
      (if (:server options)
        (start-server (:port options) (:target-path (config))))
      ;; browser
      (when-let [browser (:browser options)]
        (reset! driver
                (case browser
                  "firefox" (if-let [profile (System/getenv "FIREFOX_PROFILE")]
                              (webdriver/firefox {:profile profile})
                              (webdriver/firefox))
                  "chrome" (if-let [profile (System/getenv "CHROME_PROFILE")]
                             (webdriver/chrome {:profile profile})
                             (webdriver/chrome))
                  "safari" (webdriver/safari)))
        (webdriver/go @driver (str "http://localhost:" (:port options))))
      ;; repl
      ;; (println "Starting REPL...")
      ;; (clojure.main/repl :init #(in-ns 'ch.200ok))
      ;; (println "\nTerminating... (Force with [Ctrl-c])")
      ;; exit
      (when-not (or (:continuous options)
                    (:server options))
        (when @server
          (stop-server))
        (when @driver
          (webdriver/quit @driver))
        (System/exit 0)))))

(defn artifact-locale
  "Derive the locale of an artifact. We trust the explicit :locale key
  first (used for pages generated via `i18n: generate-for-all-locales`).
  Otherwise we try to infer it from the beginning of the canonical
  link (/en/..., /de/..., etc.). If all fails we fall back to the
  default locale."
  [artifact all-locales default-locale]
  (or (:locale artifact)
      (let [path (:canonical-link artifact)]
        (when path
          (some #(when (str/starts-with? path (str "/" (name %) "/")) %)
                all-locales)))
      default-locale))

(defn translated-url
  "Return the canonical link of ARTIFACT translated into TARGET-LOCALE.
  The function works in three tiers:
  1. If the artifact has a :translationKey we look for another artifact
     with the same key in the target locale.
  2. Otherwise we try to construct the URL by replacing the locale
     prefix (works for pages that live in /en/… or /de/… folders).
  3. As a last resort, fall back to the root of the target locale."
  [ctx artifact target-locale]
  (let [artifacts-map (:artifacts ctx)
        translation-key (:translationKey artifact)
        current-locale  (artifact-locale artifact
                                         (get-in ctx [:config :i18n :locales])
                                         (get-in ctx [:config :i18n :default-locale]))]
    (cond
      ;; 1. explicit translationKey mapping ---------------------------------
      translation-key
      (some->> artifacts-map
               vals
               (filter #(and (= (:translationKey %) translation-key)
                               (or (= (:locale %) (name target-locale))
                                   (and (:canonical-link %)
                                        (str/starts-with? (:canonical-link %) (str "/" (name target-locale) "/"))))))
               first
               :canonical-link)

      ;; 2. same path structure, just other locale ---------------------------
      current-locale
      (let [current-path (:canonical-link artifact)
            cur (name current-locale)
            tgt (name target-locale)]
        (cond
          ;; Path already starts with current locale prefix
          (and current-path (str/starts-with? current-path (str "/" cur "/")))
          (str/replace-first current-path (str "/" cur "/") (str "/" tgt "/"))

          ;; Path starts with a different locale prefix - replace it
          (and current-path
               (some #(str/starts-with? current-path (str "/" (name %) "/"))
                     (get-in ctx [:config :i18n :locales])))
          (let [existing-prefix (->> (get-in ctx [:config :i18n :locales])
                                    (map name)
                                    (filter #(str/starts-with? current-path (str "/" % "/")))
                                    first)]
            (str/replace-first current-path (str "/" existing-prefix "/") (str "/" tgt "/")))

          ;; Path doesn't have any locale prefix - add target locale prefix
          current-path
          (str "/" tgt current-path)

          ;; No path at all - fallback to root
          :else
          (str "/" tgt "/")))

      ;; 3. fallback ---------------------------------------------------------
      :else (str "/" (name target-locale) "/"))))

(defn language-switcher-html
  "Return an HTML string with language links for the current artifact."
  [ctx]
  (let [artifact (:artifact ctx)
        locales  (get-in ctx [:config :i18n :locales])
        default-locale (get-in ctx [:config :i18n :default-locale])
        current-locale (artifact-locale artifact locales default-locale)
        other-locales  (remove #(= % current-locale) locales)]
    (->> other-locales
         (map (fn [loc]
                (let [url (translated-url ctx artifact loc)
                      label (str/upper-case (name loc))]
                  (str "<a href='" url "'>" label "</a>"))))
         (str/join " | "))))
