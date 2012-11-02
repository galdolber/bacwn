;;  Copyright (c) Jeffrey Straszheim. All rights reserved.  The use and
;;  distribution terms for this software are covered by the Eclipse Public
;;  License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which can
;;  be found in the file epl-v10.html at the root of this distribution.  By
;;  using this software in any fashion, you are agreeing to be bound by the
;;  terms of this license.  You must not remove this notice, or any other,
;;  from this software.
;;
;;  magic.clj
;;
;;  A Clojure implementation of Datalog -- Magic Sets
;;
;;  straszheimjeffrey (gmail)
;;  Created 18 Feburary 2009
;;  Converted to Clojure1.4 by Martin Trojer 2012.

(ns cljs.bacwn.datalog.impl.magic
  (:require [cljs.bacwn.datalog.impl.util :as util]
            [cljs.bacwn.datalog.impl.literals :as literal]
            [cljs.bacwn.datalog.impl.rules :as rule]
            clojure.set))

;; =============================
;; Adornment

(defn adorn-query
  "Adorn a query"
  [q]
  (literal/adorned-literal q (literal/get-self-bound-cs q)))

(defn adorn-rules-set
  "Adorns the given rules-set for the given query.  (rs) is a
   rules-set, (q) is an adorned query."
  [rs q]
  (let [i-preds (rule/all-predicates rs)
        p-map (rule/predicate-map rs)]
    (loop [nrs rule/empty-rules-set ; The rules set being built
           needed #{(literal/literal-predicate q)}]
      (if (empty? needed)
        nrs
        (let [pred (first needed)
              remaining (disj needed pred)
              base-pred (literal/get-base-predicate pred)
              bindings (literal/get-adorned-bindings pred)
              new-rules (p-map base-pred)
              new-adorned-rules (map (partial rule/compute-sip bindings i-preds)
                                     new-rules)
              new-nrs (reduce conj nrs new-adorned-rules)
              current-preds (rule/all-predicates new-nrs)
              not-needed? (fn [pred]
                            (or (current-preds pred)
                                (-> pred literal/get-base-predicate i-preds not)))
              add-pred (fn [np pred]
                         (if (not-needed? pred) np (conj np pred)))
              add-preds (fn [np rule]
                          (reduce add-pred np (map literal/literal-predicate (:body rule))))
              new-needed (reduce add-preds remaining new-adorned-rules)]
          (recur new-nrs new-needed))))))


;; =============================
;; Magic !

(defn seed-relation
  "Given a magic form of a query, give back the literal form of its seed
   relation"
  [q]
  (let [pred (-> q literal/literal-predicate literal/get-base-predicate)
        bnds (-> q literal/literal-predicate literal/get-adorned-bindings)]
    (with-meta (assoc q :predicate [pred :magic-seed bnds]) {})))

(defn seed-rule
  "Given an adorned query, give back its seed rule"
  [q]
  (let [mq (literal/build-seed-bindings (literal/magic-literal q))
        sr (seed-relation mq)]
    (rule/build-rule mq [sr])))

(defn build-partial-tuple
  "Given a query and a set of bindings, build a partial tuple needed
   to extract the relation from the database."
  [q bindings]
  (into {} (remove nil? (map (fn [[k v :as pair]]
                               (if (util/is-var? v)
                                 nil
                                 (if (util/is-query-var? v)
                                   [k (bindings v)]
                                   pair)))
                             (:term-bindings q)))))

(defn seed-predicate-for-insertion
  "Given a query, return the predicate to use for database insertion."
  [q]
  (let [seed (-> q seed-rule :body first)
        columns (-> seed :term-bindings keys)
        new-term-bindings (-> q :term-bindings (select-keys columns))]
    (assoc seed :term-bindings new-term-bindings)))

(defn magic-transform
  "Return a magic transformation of an adorned rules-set (rs).  The
   (i-preds) are the predicates of the intension database.  These
   default to the predicates within the rules-set."
  ([rs]
     (magic-transform rs (rule/all-predicates rs)))
  ([rs i-preds]
     (let [not-duplicate? (fn [l mh bd]
                            (or (not (empty? bd))
                                (not (= (literal/magic-literal l)
                                        mh))))
           xr (fn [rs rule]
                (let [head (:head rule)
                      body (:body rule)
                      mh (literal/magic-literal head)
                      answer-rule (rule/build-rule head
                                              (concat [mh] body))
                      step (fn [[rs bd] l]
                             (if (and (i-preds (literal/literal-predicate l))
                                      (not-duplicate? l mh bd))
                               (let [nr (rule/build-rule (literal/magic-literal l)
                                                         (concat [mh] bd))]
                                 [(conj rs nr) (conj bd l)])
                               [rs (conj bd l)]))
                      [nrs _] (reduce step [rs []] body)]
                  (conj nrs answer-rule)))]
       (reduce xr rule/empty-rules-set rs))))

