(ns haywire128.etheric-rpg-engine.interact-test
  (:require [clojure.test :refer :all]
            [haywire128.etheric-rpg-engine.core :as c]
            [haywire128.etheric-rpg-engine.shell :as shell]
            [haywire128.etheric-rpg-engine.interact :as interact]
            [clojure.java.io :as io]
            [datahike.api :as d]))

(deftest interact-session-persistence-test
  (testing "Single-turn playtesting harness correctly persists last-turn narrative and action"
    (let [temp-db-dir (io/file "data/test-rpg-db")
          temp-env-file (io/file "data/test-env-state.edn")
          ;; Redefine database config and env file to avoid clobbering active user data
          test-db-config {:store {:backend :file :path (.getPath temp-db-dir)}}]
      (try
        ;; Cleanup from previous failed runs if any
        (when (d/database-exists? test-db-config)
          (d/delete-database test-db-config))
        (when (.exists temp-env-file)
          (.delete temp-env-file))
        
        (with-redefs [interact/db-config test-db-config
                      interact/env-file temp-env-file]
          ;; Mock LLM client returns code that resolves narrative and finalizes
          (let [mock-root-code (str "(do (finalize! !env {:narrative \"Turn narrative details.\" :success true}))")
                mock-llm (reify c/LLM
                           (complete [_ messages _model _opts]
                             {:content mock-root-code :cost 0}))]
            (with-redefs [shell/llm-client (fn [] mock-llm)]
              ;; 1. Initialize session
              (let [init-res (interact/init-session! {:player/name "Sage" :player/genre :medieval-fantasy})]
                (is (:success init-res))
                (is (= "Turn narrative details." (get-in init-res [:result :narrative]))))
              
              ;; 2. Step session
              (let [step-res (interact/step-session! "look around")]
                (is (:success step-res))
                ;; 3. Verify that the env now contains the updated last-action and last-narrative!
                (let [env (interact/deserialize-env)]
                  (is (= "look around" (c/env-get env :last-action)))
                  (is (= "Turn narrative details." (c/env-get env :last-narrative))))))))
        (finally
          ;; Cleanup temp files and DB
          (when (d/database-exists? test-db-config)
            (d/delete-database test-db-config))
          (when (.exists temp-env-file)
            (.delete temp-env-file)))))))
