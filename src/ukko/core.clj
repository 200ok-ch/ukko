(ns ukko.core
  (:gen-class)
  (:require ;; [org.httpkit.server :as server]
            ;; [hawk.core :as hawk]
            [yaml.core :as yaml]
            [compojure.route :as route]
            [compojure.core :refer [defroutes]]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.term.colors :as color]
            [fsdb.core :as fsdb]
            [fleet :refer [fleet]])
  (:import [java.util Timer TimerTask]))

;; (defn debounce
;;   ([f] (debounce f 1000))
;;   ([f timeout]
;;    (let [timer (Timer.)
;;          task (atom nil)]
;;      (with-meta
;;        (fn [& args]
;;          (when-let [t ^TimerTask @task]
;;            (.cancel t))
;;          (let [new-task (proxy [TimerTask] []
;;                           (run []
;;                             (apply f args)
;;                             (reset! task nil)
;;                             (.purge timer)))]
;;            (reset! task new-task)
;;            (.schedule timer new-task timeout)))
;;        {:task-atom task}))))

;; (defn progress [color & args]
;;   (if (fn? color)
;;     (let [[prefix & msg] args]
;;       (println (color prefix) (str/join " " msg))
;;       (println (color/blue color) (str/join	" " args)))))

(defmulti transform (fn [f _ _] f))

(defn- pandoc [f t]
  (:out (shell/sh "pandoc" "-f" f "-t" "html" "-" :in t)))

(defmethod transform :org [_ template _]
  (pandoc "org" template))

(defmethod transform :md [_ template _]
  (pandoc "markdown" template))

(defmethod transform :fleet [_ template ctx]
  (.toString ((fleet [ctx] template) ctx)))

;; --------------------------------------------------------------------------------

(def mmap pmap)

;; (def port 8080)
;;
;; (defonce server (atom nil))
;;
;; (defn stop-server []
;;   (when-not (nil? @server)
;;     (@server :timeout 100)
;;     (reset! server nil)))
;;
;; #_(stop-server)
;;
;; (defroutes routes
;;   (route/files "/")
;;   (route/not-found "<p>Page not found.</p>"))
;;
;; (defn start-server []
;;   (println (color/green "Server running... (terminate with Ctrl-c)"))
;;   (reset! server (server/run-server routes {:port port
;;                                             :event-logger println})))

;; --------------------------------------------------------------------------------

(defn sh [& args]
  (shell/sh "bash" "-c" (str/join " " args)))

(defn find-files [path]
  (println (color/blue "Finding files in") path)
  (->> path
       clojure.java.io/file
       file-seq
       (filter #(.isFile %))
       (mapv #(.getPath %))))

(def defaults
  {:assets-path "assets"
   :data-path "data"
   :site-path "site"
   :layouts-path "layouts"
   :target-path "public"
   :target-extension ".html"
   :format "passthrough"
   :layout ["post" "blog"]})

(defn config []
  ;; FIXME: should be a deep merge
  (merge defaults
         (when (.exists (io/file "ukko.yml"))
           (-> "ukko.yml"
               slurp
               yaml/parse-string))))

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

#_(let [[a & b] (str/split "a" #"-")] b)
#_(parse-file "../200ok.ch-ukko/site/main.html")

;; in most cases
(defmulti process (fn [format & _] format))

(defmethod process :default [format {:keys [path template] :as artifact} _]
  (println (color/red "Unknown format") (name format) (color/red "for file") path)
  (assoc artifact :contents template))

(defmethod process :hide [_ artifact _]
  (assoc artifact :contents []))

(defmethod process :copy [_ {:keys [path target-path] :as artifact} _]
  (sh "rsync -a" path target-path)
  (assoc artifact :contents []))

(defmethod process :passthrough [_ {:keys [template] :as artifact} _]
  (assoc artifact :contents template))

(defmethod process :md [_ {:keys [path] :as artifact} _]
  (assoc artifact :contents (:out (sh "pandoc -f markdown -t html" path))))

(defmethod process :org [_ {:keys [path] :as artifact} _]
  (assoc artifact :contents (:out (sh "pandoc -f org -t html" path))))

(defmethod process :scss [_ {:keys [path template target-path] :as artifact} _]
  (let [directory (str/replace path #"/[^/]+$" "")
        sass "/home/phil/.nvm/versions/node/v11.2.0/bin/sass"
        {:keys [err out]} (shell/sh sass "--stdin" "-I" directory :in template)]
    (when err
      (println (color/red err)))
    (assoc artifact :contents out)))

#_(shell/sh "/home/phil/.rbenv/shims/scss" :in "a {b:c;}")

(defmethod process :fleet [_ {:keys [scope template] :as artifact} ctx]
  (let [scoped (get-in (merge ctx artifact) (read-string (str "[" scope "]")))
        result (transform :fleet template scoped)]
    (assoc artifact :contents result)))

;; (defmethod process :yield [_ {:keys [template yield] :as artifact} _]
;;   (assoc artifact :contents (str/replace template "$yield$" yield)))

(defn add-defaults [config artifact]
  ;; FIXME: should be a deep merge
  (merge config artifact))

;; (defn make-target
;;   "Takes an artifact and returns its target."
;;   [{:keys [path site-path target-path target-extension]}]
;;   (-> path
;;       (str/replace #"\..+$" "")
;;       (str/replace (str site-path "/")
;;                    (str target-path "/"))
;;       (str target-extension)))

#_(make-target "./resources/asdf.md" ".html")
#_(make-target "./resources/asdf.md" "/index.html")

(defn make-id [{:keys [path]} base]
  (-> path
      (str/replace #"\..+$" "")
      (str/replace (str base "/") "")))

(defn apply-layout [{:keys [layouts] :as ctx}
                    artifact
                    {:keys [output] :as content}
                    layout]
  (if-let [template (get layouts layout)]
    (as-> template %
      (:template %)
      ;; atm, layouts always need to be pandoc style templates
      (transform (-> template :format keyword) % (assoc ctx :artifact artifact :output output))
      ;; (str/replace % "$yield$" output)
      (assoc content :output %))
    content))

(defn apply-layouts [ctx {:keys [layout] :as artifact} content]
  (->> layout
       vector
       flatten
       (reduce (partial apply-layout ctx artifact) content)))

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

(defn fix-format [field artifact]
  (if (field artifact)
    (update artifact field #(.format (java.text.SimpleDateFormat. "yyyy-MM-dd") %))
    artifact))

(defn normalize-content [content]
  (cond
    (sequential? content)
    (map normalize-content content)
    (associative? content)
    [content]
    (string? content)
    [{:output content}]))

(defn process-artifact
  "Returns the CTX with the artifact referenced by ARTIFACT-ID fully
  processed. Adds `:contents` to the artifact."
  [ctx {:keys [path format] :as artifact}]
  (println (color/blue "Processing artifact") (:id artifact) format)
  ;; (print ".")
  (as-> format %
    (vector %)
    (flatten %)
    (reduce #(process (keyword %2) %1 ctx) artifact %)
    (update % :contents normalize-content)
    (update % :contents (partial map (partial apply-layouts ctx %)))))

(defn process-artifact-id
  [{:keys [data artifacts] :as ctx} artifact-id]
  (->> (get artifacts artifact-id)
       (process-artifact ctx)
       (assoc-in ctx [:artifacts artifact-id])))

(defn cartesian-product [colls]
  (if (empty? colls)
    '(())
    (for [more (cartesian-product (rest colls))
          x (first colls)]
      (cons x more))))

(defn modify-id [id fragment]
  (println (color/magenta id) fragment)
  (str/replace id #"[^/]+$" (str fragment)))

(defn handle-artifact [ctx {:keys [id collection] :as artifact}]
  (cond
    (string? collection)
    (->> (get-in ctx (read-string (str "[" collection "]")))
         (reduce-kv #(conj %1 (assoc %3 :id (modify-id id (name %2)))) [])
         (map (partial merge artifact)))
    (associative? collection)
    (->> collection
         (reduce-kv #(assoc %1 %2 (get-in ctx (read-string (str "[" %3 "]")))) {})
         (reduce-kv #(assoc %1 %2 (reduce-kv (fn [a b c] (conj a (assoc c :id (name b)))) [] %3)) {})
         vals
         cartesian-product
         (map (partial interleave (keys collection)))
         (map (partial apply hash-map))
         (map #(assoc % :id (modify-id id (str/join "-" (map :id (vals %))))))
         (map (partial merge artifact)))
    (nil? collection)
    (->> artifact
         add-canonical-link
         add-word-count
         add-ttr
         (fix-format :date-published)
         vector)))

#_(handle-artifact {:tech {:clojure {:b 1} :script {:b 2}} :serv {:coding {}}} {:id "kladdera/datsch" :collection {:tech ":tech" :serv ":serv"} :template "<h1>"})

(defn generate! []
  (println (color/blue "Generating site..."))
  ;; TODO: measure time and display result on complete
  (let [{:keys [data-path assets-path target-path site-path layouts-path] :as config} (config)]
    (sh "rsync -a" (str assets-path "/") target-path)
    (let [data (fsdb/read-tree data-path)
          layout-files (find-files layouts-path)
          layouts (mmap parse-file layout-files)
          layouts (mmap (partial add-id layouts-path) layouts)
          layouts (reduce #(assoc %1 (:id %2) %2) {} layouts)
          files (find-files site-path)
          artifacts (mmap parse-file files)
          artifacts (mmap (partial add-defaults config) artifacts)
          artifacts (mmap (partial add-id site-path) artifacts)
          artifacts (apply concat (mmap (partial handle-artifact {:data data}) artifacts))
          artifacts (mmap add-target artifacts)
          artifacts-map (reduce #(assoc %1 (:id %2) %2) {} artifacts)
          context {:data data :layouts layouts :artifacts artifacts-map}
          ;; one time: instead trampoline it
          context² (reduce process-artifact-id context (-> context :artifacts keys sort))
          artifacts (->> context² :artifacts vals (sort-by :id))]
      (println (color/green "Number of artifacts:") (count artifacts))
      (doall
       (for [{:keys [id contents hidden] :as artifact} artifacts]
         (if hidden
           (println (color/yellow "Skipping hidden artifact") id)
           ;; (print ".")
           (doall
            (for [{:keys [output] :as content} contents]
              (let [target (or (:target content) (:target artifact))]
                ;; (print ".")
                (println (color/blue "Writing") target (str "(" (count output) " bytes)"))
                ;; TODO: measure write time
                (io/make-parents target)
                (spit target output)))))))))
  (println (color/green "Complete.")))

;; (defn pandoc [state & args]
;;   (let [{:keys [extensions cmd-opts]} (apply hash-map args)]
;;     (for [ext extensions]
;;       (for [file (file-glob (str "*" ext))]
;;         (let [target (make-target file "/index.html")]
;;           (io/make-parents target)
;;           (sh "pandoc" cmd-options ">" target))))))
;;
;; (defn build []
;;   (-> {}
;;       ;; TODO: read structured data via fsdb
;;       ;; build html files from markdown files
;;       (pandoc :extensions [".md" ".markdown"]
;;               :cmd-opts ["-f markdown -t html5"])
;;
;;       ;; TODO: build html files from org-mode files
;;       ;; TODO: remove drafts
;;       ;; TODO: build slugs?
;;       ;; TODO: calculate ttr (time to read)
;;       ;; TODO: categories?
;;       ;; TODO: count words
;;       ;; TODO: date?
;;       ;; TODO: build blog index, paginated
;;       ;; TODO: build static pages
;;       ))

#_(start-server)

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  ;; (let [paths [(:site-path (config))
  ;;              (:layouts-path (config))
  ;;              (:assets-path (config))
  ;;              (:data-path (config))]]
  ;;   (println (color/blue "Watching files..."))
  ;;   (doall
  ;;    (for [path paths]
  ;;      (println "->" path)))
  ;;   (hawk/watch!
  ;;    [{:paths paths
  ;;      ;; TODO: debounce events
  ;;      :handler (debounce (fn [ctx {:keys [kind file]}]
  ;;                           ;; (println kind "=>" (.getPath file))
  ;;                           (generate!)))}]))
  (generate!)
  ;; (start-server)
  ;; (println "Starting REPL...")
  ;; (clojure.main/repl :init #(in-ns 'ch.200ok))
  ;; (println "\nTerminating... (Force with [Ctrl-c])")
  ;;(stop-server)
  (System/exit 0)
  )
