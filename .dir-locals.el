((clojure-mode

  ;; select deps as the build tool
  (cider-preferred-build-tool . clojure-cli)
  ;; include dev dependencies when running
  (cider-clojure-cli-global-options . "-A:dev:test")))
