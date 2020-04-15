(defproject serenity "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/clojurescript "1.10.597"]
                 [org.clojure/tools.cli "1.0.194"]
                 [com.taoensso/sente "1.14.0-RC2"]
                 [http-kit "2.3.0"]
                 [compojure "1.6.1"]
                 [ring-logger-timbre "0.7.6"]
                 [ring/ring-defaults "0.3.2"]
                 [hiccup "1.0.5"]
                 [environ "1.1.0"]
                 [com.taoensso/timbre "4.10.0"]]
  :main ^:skip-aot serenity.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  :plugins
  [[lein-cljsbuild "1.1.7"]
   [cider/cider-nrepl   "0.22.4"]]
  :cljsbuild
  {:builds
   [{:id :cljs-client
     :source-paths ["src"]
     :compiler {:output-to "resources/public/main.js"
                :optimizations :whitespace
                :npm-deps {:util "0.12.2"
                           :simple-peer "v9.6.2"}
                :install-deps true
                :pretty-print true}}]}
  :clean-targets ^{:protect false} ["resources/public/cljs-runtime"]
  :aliases {"start" ["do" "clean," "cljsbuild" "once," "run"]})
