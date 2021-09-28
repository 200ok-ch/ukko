(defproject ukko "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [http-kit "2.5.3"]
                 [hawk "0.2.11"]
                 [compojure "1.6.2"]
                 [io.forward/yaml "1.0.11"]
                 [clojure-term-colors "0.1.0"]
                 [fsdb "1.1.1"]
                 [fleet "0.10.2"]]
  :main ^:skip-aot ukko.core
  :target-path "target/%s"
  :bin {:name "ukko"
        :bin-path "~/bin"
        :jvm-opts ["-server" "-Dfile.encoding=utf-8" "$JVM_OPTS" ]}
  :profiles {:uberjar {:aot :all}
             :dev {:plugins [[lein-binplus "0.6.6"]]}})
