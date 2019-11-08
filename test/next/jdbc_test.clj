;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc-test
  "Not exactly a test suite -- more a series of examples."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as c]
            [next.jdbc.test-fixtures :refer [with-test-db db ds
                                              derby? postgres?]]
            [next.jdbc.prepare :as prep]
            [next.jdbc.result-set :as rs]
            [next.jdbc.specs :as specs])
  (:import (java.sql ResultSet ResultSetMetaData)))

(set! *warn-on-reflection* true)

(use-fixtures :once with-test-db)

(specs/instrument)

(deftest basic-tests
  (testing "plan"
    (is (= "Apple"
           (reduce (fn [_ row] (reduced (:name row)))
                   nil
                   (jdbc/plan
                    (ds)
                    ["select * from fruit where appearance = ?" "red"])))))
  (testing "execute-one!"
    (is (nil? (jdbc/execute-one!
               (ds)
               ["select * from fruit where appearance = ?" "neon-green"])))
    (is (= "Apple" ((if (postgres?) :fruit/name :FRUIT/NAME)
                    (jdbc/execute-one!
                     (ds)
                     ["select * from fruit where appearance = ?" "red"])))))
  (testing "execute!"
    (let [rs (jdbc/execute!
              (ds)
              ["select * from fruit where appearance = ?" "neon-green"])]
      (is (vector? rs))
      (is (= [] rs)))
    (let [rs (jdbc/execute!
              (ds)
              ["select * from fruit where appearance = ?" "red"])]
      (is (= 1 (count rs)))
      (is (= 1 ((if (postgres?) :fruit/id :FRUIT/ID) (first rs)))))
    (let [rs (jdbc/execute!
              (ds)
              ["select * from fruit order by id"]
              {:builder-fn rs/as-maps})]
      (is (every? map? rs))
      (is (every? meta rs))
      (is (= 4 (count rs)))
      (is (= 1 ((if (postgres?) :fruit/id :FRUIT/ID) (first rs))))
      (is (= 4 ((if (postgres?) :fruit/id :FRUIT/ID) (last rs)))))
    (let [rs (jdbc/execute!
              (ds)
              ["select * from fruit order by id"]
              {:builder-fn rs/as-arrays})]
      (is (every? vector? rs))
      (is (= 5 (count rs)))
      (is (every? #(= 5 (count %)) rs))
      ;; columns come first
      (is (every? qualified-keyword? (first rs)))
      ;; :FRUIT/ID should be first column
      (is (= (if (postgres?) :fruit/id :FRUIT/ID) (ffirst rs)))
      ;; and all its corresponding values should be ints
      (is (every? int? (map first (rest rs))))
      (is (every? string? (map second (rest rs)))))
    (let [rs (jdbc/execute! ; test again, with adapter and lower columns
              (ds)
              ["select * from fruit order by id"]
              {:builder-fn (rs/as-arrays-adapter
                            rs/as-lower-arrays
                            (fn [^ResultSet rs _ ^Integer i]
                              (.getObject rs i)))})]
      (is (every? vector? rs))
      (is (= 5 (count rs)))
      (is (every? #(= 5 (count %)) rs))
      ;; columns come first
      (is (every? qualified-keyword? (first rs)))
      ;; :fruit/id should be first column
      (is (= :fruit/id (ffirst rs)))
      ;; and all its corresponding values should be ints
      (is (every? int? (map first (rest rs))))
      (is (every? string? (map second (rest rs)))))
    (let [rs (jdbc/execute!
              (ds)
              ["select * from fruit order by id"]
              {:builder-fn rs/as-unqualified-maps})]
      (is (every? map? rs))
      (is (every? meta rs))
      (is (= 4 (count rs)))
      (is (= 1 ((if (postgres?) :id :ID) (first rs))))
      (is (= 4 ((if (postgres?) :id :ID) (last rs)))))
    (let [rs (jdbc/execute!
              (ds)
              ["select * from fruit order by id"]
              {:builder-fn rs/as-unqualified-arrays})]
      (is (every? vector? rs))
      (is (= 5 (count rs)))
      (is (every? #(= 5 (count %)) rs))
      ;; columns come first
      (is (every? simple-keyword? (first rs)))
      ;; :ID should be first column
      (is (= (if (postgres?) :id :ID) (ffirst rs)))
      ;; and all its corresponding values should be ints
      (is (every? int? (map first (rest rs))))
      (is (every? string? (map second (rest rs))))))
  (testing "prepare"
    (let [rs (with-open [con (jdbc/get-connection (ds))
                         ps  (jdbc/prepare
                              con
                              ["select * from fruit order by id"])]
                 (jdbc/execute! ps))]
      (is (every? map? rs))
      (is (every? meta rs))
      (is (= 4 (count rs)))
      (is (= 1 ((if (postgres?) :fruit/id :FRUIT/ID) (first rs))))
      (is (= 4 ((if (postgres?) :fruit/id :FRUIT/ID) (last rs)))))
    (let [rs (with-open [con (jdbc/get-connection (ds))
                         ps  (jdbc/prepare
                              con
                              ["select * from fruit where id = ?"])]
                 (jdbc/execute! (prep/set-parameters ps [4])))]
      (is (every? map? rs))
      (is (every? meta rs))
      (is (= 1 (count rs)))
      (is (= 4 ((if (postgres?) :fruit/id :FRUIT/ID) (first rs))))))
  (testing "transact"
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/transact (ds)
                          (fn [t] (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"]))
                          {:rollback-only true})))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "with-transaction rollback-only"
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/with-transaction [t (ds) {:rollback-only true}]
             (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"]))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "with-transaction exception"
    (is (thrown? Throwable
           (jdbc/with-transaction [t (ds)]
             (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])
             (throw (ex-info "abort" {})))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "with-transaction call rollback"
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/with-transaction [t (ds)]
             (let [result (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])]
               (.rollback t)
               result))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "with-transaction with unnamed save point"
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/with-transaction [t (ds)]
             (let [save-point (.setSavepoint t)
                   result (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])]
               (.rollback t save-point)
               result))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "with-transaction with named save point"
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/with-transaction [t (ds)]
             (let [save-point (.setSavepoint t (name (gensym)))
                   result (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])]
               (.rollback t save-point)
               result))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"]))))))

(deftest connection-tests
  (testing "datasource via jdbcUrl"
    (when-not (postgres?)
      (let [[url etc] (#'c/spec->url+etc (db))
            ds (jdbc/get-datasource (assoc etc :jdbcUrl url))]
        (if (derby?)
          (is {:create true} etc)
          (is {} etc))
        (is (instance? javax.sql.DataSource ds))
        (is (str/index-of (pr-str ds) (str "jdbc:" (:dbtype (db)))))
        ;; checks get-datasource on a DataSource is identity
        (is (identical? ds (jdbc/get-datasource ds)))
        (with-open [con (jdbc/get-connection ds {})]
          (is (instance? java.sql.Connection con)))))))

(deftest plan-misuse
  (let [s (pr-str (jdbc/plan (ds) ["select * from fruit"]))]
    (is (re-find #"missing reduction" s)))
  (let [s (pr-str (into [] (jdbc/plan (ds) ["select * from fruit"])))]
    (is (re-find #"missing `map` or `reduce`" s)))
  ;; this may succeed or not, depending on how the driver handles things
  ;; most drivers will error because the ResultSet was closed before pr-str
  ;; is invoked (which will attempt to print each row)
  (let [s (pr-str (into [] (take 3) (jdbc/plan (ds) ["select * from fruit"])))]
    (is (or (re-find #"missing `map` or `reduce`" s)
            (re-find #"(?i)^\[#:fruit\{.*:id.*\}\]$" s))))
  (is (every? #(re-find #"(?i)^#:fruit\{.*:id.*\}$" %)
              (into [] (map str) (jdbc/plan (ds) ["select * from fruit"]))))
  (is (every? #(re-find #"(?i)^#:fruit\{.*:id.*\}$" %)
              (into [] (map pr-str) (jdbc/plan (ds) ["select * from fruit"]))))
  (is (thrown? IllegalArgumentException
               (doall (take 3 (jdbc/plan (ds) ["select * from fruit"]))))))

(deftest adapter-side-effects
  (let [logging (atom [])
        logger  (fn [data] (swap! logging conj data))
        sql-p   ["select * from fruit where id in (?,?) order by id desc" 1 4]]
    (jdbc/execute! (ds) sql-p
                   {:builder-fn (rs/builder-adapter
                                 rs/as-lower-maps
                                 {:sql-params-fn logger
                                  :row!-fn logger
                                  :rs!-fn logger})})
    ;; should log four things
    (is (= 4     (-> @logging count)))
    ;; :next.jdbc/sql-params value
    (is (= sql-p (-> @logging (nth 0))))
    ;; first row (with PK 4)
    (is (= 4     (-> @logging (nth 1) :fruit/id)))
    ;; second row (with PK 1)
    (is (= 1     (-> @logging (nth 2) :fruit/id)))
    ;; full result set with two rows
    (is (= 2     (-> @logging (nth 3) count)))
    (is (= [4 1] (-> @logging (nth 3) (->> (map :fruit/id)))))
    ;; now repeat without the row logging
    (reset! logging [])
    (jdbc/execute! (ds) sql-p
                   {:builder-fn (rs/builder-adapter
                                 rs/as-lower-maps
                                 {:sql-params-fn logger
                                  :rs!-fn logger})})
    ;; should log two things
    (is (= 2     (-> @logging count)))
    ;; :next.jdbc/sql-params value
    (is (= sql-p (-> @logging (nth 0))))
    ;; full result set with two rows
    (is (= 2     (-> @logging (nth 1) count)))
    (is (= [4 1] (-> @logging (nth 1) (->> (map :fruit/id)))))))
