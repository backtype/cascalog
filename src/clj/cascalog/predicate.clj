 ;    Copyright 2010 Nathan Marz
 ; 
 ;    This program is free software: you can redistribute it and/or modify
 ;    it under the terms of the GNU General Public License as published by
 ;    the Free Software Foundation, either version 3 of the License, or
 ;    (at your option) any later version.
 ; 
 ;    This program is distributed in the hope that it will be useful,
 ;    but WITHOUT ANY WARRANTY; without even the implied warranty of
 ;    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 ;    GNU General Public License for more details.
 ; 
 ;    You should have received a copy of the GNU General Public License
 ;    along with this program.  If not, see <http://www.gnu.org/licenses/>.

(ns cascalog.predicate
  (:use [clojure.contrib.seq-utils :only [find-first]])
  (:use [cascalog vars util])
  (:require [cascalog [workflow :as w]])
  (:import [java.util ArrayList])
  (:import [cascading.tap Tap])
  (:import [cascading.tuple Fields])
  (:import [cascalog ClojureParallelAggregator ClojureBuffer ClojureBufferCombiner CombinerSpec CascalogFunction CascalogFunctionExecutor]))

;; doing it this way b/c pain to put metadata directly on a function
;; assembly-maker is a function that takes in infields & outfields and returns
;; [preassembly postassembly]
(defstruct parallel-aggregator :type :init-var :combine-var :args)

;; :num-intermediate-vars-fn takes as input infields, outfields
(defstruct parallel-buffer :type :hof? :init-hof-var :combine-hof-var :extract-hof-var :num-intermediate-vars-fn :buffer-hof-var)


(defmacro defparallelagg [name & body]
  `(def ~name (struct-map cascalog.predicate/parallel-aggregator :type ::parallel-aggregator ~@body)))

(defmacro defparallelbuf [name & body]
  `(def ~name (struct-map cascalog.predicate/parallel-buffer :type ::parallel-buffer ~@body)))

;; ids are so they can be used in sets safely
(defmacro defpredicate [name & attrs]
  `(defstruct ~name :type :id ~@attrs))

(defmacro predicate [aname & attrs]
  `(struct ~aname ~(keyword (name aname)) (uuid) ~@attrs))

;; for map, mapcat, and filter
(defpredicate operation :assembly :infields :outfields)

;; return a :post-assembly, a :parallel-agg, and a :serial-agg-assembly
(defpredicate aggregator :buffer? :parallel-agg :pregroup-assembly :serial-agg-assembly :post-assembly :infields :outfields)
;; automatically generates source pipes and attaches to sources
(defpredicate generator :ground? :sourcemap :pipe :outfields :trapmap)

(defpredicate option :key :val)

(defpredicate predicate-macro :pred-fn)


;; TODO: change this to use fast first buffer
(def distinct-aggregator (predicate aggregator false nil identity (w/first) identity [] []))


(defstruct predicate-variables :in :out)

(defn- implicit-var-flag [vars selector-default]
  (if (find-first keyword? vars)
    :<
    selector-default))

(defn- mk-args-map [normed-vars]
  (let [partitioned (partition-by keyword? normed-vars)
        keys (map first (take-nth 2 partitioned))
        vals (take-nth 2 (rest partitioned))]
      (zipmap keys vals)))

(defn- vectorify-arg [argsmap sugararg outarg]
  (cond (not (or (contains? argsmap sugararg) (contains? argsmap outarg)))
          argsmap
        (contains? argsmap outarg) (assoc argsmap outarg (first (argsmap outarg)))
        true (assoc argsmap outarg (argsmap sugararg))
    ))

(defn vectorify-pos-selector [argsmap]
  (if-let [[amt selector-map] (argsmap :#>)]
    (let [all-post-map (reduce (fn [m i]
                                  (assoc m i (if-let [v (selector-map i)]
                                    v (gen-nullable-var))))
                               {}
                               (range amt))]
      (assoc argsmap :>> (map second (sort-by first (seq all-post-map)))))
      argsmap ))

(defn parse-variables
  "parses variables of the form ['?a' '?b' :> '!!c']
   If there is no :>, defaults to flag-default"
  [vars selector-default]
  (let [vars (if (keyword? (first vars)) vars (cons (implicit-var-flag vars selector-default) vars))
        argsmap (-> vars (mk-args-map) (vectorify-arg :> :>>) (vectorify-arg :< :<<) (vectorify-pos-selector))
        ret {:<< (:<< argsmap) :>> (:>> argsmap)}]
        (if-not (#{:< :>} selector-default)
          (assoc ret selector-default (argsmap selector-default))
          ret )))

;; hacky, but best way to do it given restrictions of needing a var for regular functions, needing 
;; to seemlessly integrate with normal workflows, and lack of function metadata in clojure (until 1.2 anyway)
;; uses hacky function metadata so that operations can be passed in as arguments when constructing cascalog
;; rules
(defn- predicate-dispatcher [op & rest]
  (let [ret (cond (keyword? op) ::option
        (instance? Tap op) ::tap
        (instance? CascalogFunction op) ::cascalog-function
        (map? op) (:type op)
        (w/get-op-metadata op) (:type (w/get-op-metadata op))
        (fn? op) ::vanilla-function
        true (throw (IllegalArgumentException. "Bad predicate"))
        )]
    (if (= ret :bufferiter) :buffer ret)))

(defn predicate-macro? [p] (= :predicate-macro (predicate-dispatcher p)))

(defmulti predicate-default-var predicate-dispatcher)

(defmethod predicate-default-var ::option [& args] :<)
(defmethod predicate-default-var ::tap [& args] :>)
(defmethod predicate-default-var :generator [& args] :>)
(defmethod predicate-default-var ::parallel-aggregator [& args] :>)
(defmethod predicate-default-var ::parallel-buffer [& args] :>)
(defmethod predicate-default-var ::vanilla-function [& args] :<)
(defmethod predicate-default-var :map [& args] :>)
(defmethod predicate-default-var :mapcat [& args] :>)
(defmethod predicate-default-var :aggregate [& args] :>)
(defmethod predicate-default-var :buffer [& args] :>)
(defmethod predicate-default-var :filter [& args] :<)
(defmethod predicate-default-var ::cascalog-function [& args] :>)

(defmulti hof-predicate? predicate-dispatcher)

(defmethod hof-predicate? ::option [& args] false)
(defmethod hof-predicate? ::tap [& args] false)
(defmethod hof-predicate? :generator [& args] false)
(defmethod hof-predicate? ::parallel-aggregator [& args] false)
(defmethod hof-predicate? ::parallel-buffer [op & args] (:hof? op))
(defmethod hof-predicate? ::vanilla-function [& args] false)
(defmethod hof-predicate? :map [op & args] (:hof? (w/get-op-metadata op)))
(defmethod hof-predicate? :mapcat [op & args] (:hof? (w/get-op-metadata op)))
(defmethod hof-predicate? :aggregate [op & args] (:hof? (w/get-op-metadata op)))
(defmethod hof-predicate? :buffer [op & args] (:hof? (w/get-op-metadata op)))
(defmethod hof-predicate? :filter [op & args] (:hof? (w/get-op-metadata op)))
(defmethod hof-predicate? ::cascalog-function [op & args] false)

(defmulti build-predicate-specific predicate-dispatcher)

(defn- ground-fields? [outfields]
  (every? ground-var? outfields))

(defn- init-trap-map [options]
  (if-let [trap (:trap options)]
    {(:name trap) (:tap trap)}
    {} ))

(defn- init-pipe-name [options]
   (if-let [trap (:trap options)]
    (:name trap)
    (uuid) ))

(defmethod build-predicate-specific ::tap [tap _ _ infields outfields options]
  (let
    [sourcename (uuid)
     pname (init-pipe-name options)
     pipe (w/assemble (w/pipe sourcename) (w/pipe-rename pname) (w/identity Fields/ALL :fn> outfields :> Fields/RESULTS))]
    (when-not (empty? infields) (throw (IllegalArgumentException. "Cannot use :> in a taps vars declaration")))
    (predicate generator (ground-fields? outfields) {sourcename tap} pipe outfields (init-trap-map options))
  ))

(defmethod build-predicate-specific :generator [gen _ _ infields outfields options]
  (let [pname (init-pipe-name options)
        trapmap (merge (:trapmap gen) (init-trap-map options))
        gen-pipe (w/assemble (:pipe gen) (w/pipe-rename pname) (w/identity Fields/ALL :fn> outfields :> Fields/RESULTS))]
  (predicate generator (ground-fields? outfields) (:sourcemap gen) gen-pipe outfields trapmap )))

(defmethod build-predicate-specific ::vanilla-function [_ opvar _ infields outfields options]
  (when (nil? opvar) (throw (RuntimeException. "Functions must have vars associated with them")))
  (let
    [[func-fields out-selector] (if (not-empty outfields) [outfields Fields/ALL] [nil nil])
     assembly (w/filter opvar infields :fn> func-fields :> out-selector)]
    (predicate operation assembly infields outfields)))

(defn- hof-prepend [hof-args & args]
  (if hof-args (cons hof-args args) args))

(defn- simpleop-build-predicate [op _ hof-args infields outfields options]
    (predicate operation (apply op (hof-prepend hof-args infields :fn> outfields :> Fields/ALL)) infields outfields))

(defmethod build-predicate-specific :map [& args]
  (apply simpleop-build-predicate args))

(defmethod build-predicate-specific :mapcat [& args]
  (apply simpleop-build-predicate args))

(defmethod build-predicate-specific :filter [op _ hof-args infields outfields options]
  (let [[func-fields out-selector] (if (not-empty outfields) [outfields Fields/ALL] [nil nil])
     assembly (apply op (hof-prepend hof-args infields :fn> func-fields :> out-selector))]
    (predicate operation assembly infields outfields)))

(defmethod build-predicate-specific ::cascalog-function [op _ _ infields outfields options]
  (predicate operation (w/raw-each (w/fields infields) (CascalogFunctionExecutor. (w/fields outfields) op) Fields/ALL)
                       infields
                       outfields))

(defn- mk-hof-fn-spec [avar args]
  (w/fn-spec (cons avar args)))

(defmethod build-predicate-specific ::parallel-buffer [pbuf _ hof-args infields outfields options]
  (let [temp-vars (gen-nullable-vars ((:num-intermediate-vars-fn pbuf) infields outfields))
        hof-args  (cons (dissoc options :trap) hof-args)
        combiner-spec (CombinerSpec. (mk-hof-fn-spec (:init-hof-var pbuf) hof-args)
                                     (mk-hof-fn-spec (:combine-hof-var pbuf) hof-args)
                                     (mk-hof-fn-spec (:extract-hof-var pbuf) hof-args))
        combiner (fn [group-fields]
                    (w/raw-each Fields/ALL
                                (ClojureBufferCombiner.
                                            (w/fields group-fields)
                                            (w/fields (:sort options))
                                            (w/fields infields)
                                            (w/fields temp-vars)
                                            combiner-spec)
                                Fields/RESULTS))
        group-assembly (w/raw-every (w/fields temp-vars)
                                    (ClojureBuffer. (w/fields outfields)
                                                    (mk-hof-fn-spec (:buffer-hof-var pbuf) hof-args)
                                                    false)
                                    Fields/ALL)]
       (predicate aggregator true combiner identity group-assembly identity infields outfields)))

(defmethod build-predicate-specific ::parallel-aggregator [pagg _ _ infields outfields options]
  (when (or (not= (count infields) (:args pagg)) (not= 1 (count outfields)))
    (throw (IllegalArgumentException. (str "Invalid # input fields to aggregator " pagg))))
  (let [init-spec (w/fn-spec (:init-var pagg))
        combine-spec (w/fn-spec (:combine-var pagg))
        cascading-agg (ClojureParallelAggregator. (first outfields) init-spec combine-spec (:args pagg))
        serial-assem (if (empty? infields)
                        (w/raw-every cascading-agg Fields/ALL)
                        (w/raw-every (w/fields infields)
                                  cascading-agg
                                  Fields/ALL))]
    (predicate aggregator false (assoc pagg :outfield (first outfields)) identity serial-assem identity infields outfields)))

(defn- simpleagg-build-predicate [buffer? op _ hof-args infields outfields options]
  (predicate aggregator buffer? nil identity (apply op (hof-prepend hof-args infields :fn> outfields :> Fields/ALL)) identity infields outfields))

(defmethod build-predicate-specific :aggregate [& args]
  (apply simpleagg-build-predicate false args))

(defmethod build-predicate-specific :buffer [& args]
  (apply simpleagg-build-predicate true args))

(defn- variable-substitution
  "Returns [newvars {map of newvars to values to substitute}]"
  [vars]
  (substitute-if (complement cascalog-var?) (fn [_] (gen-nullable-var)) vars))

(defn- output-substitution
  "Returns [{newvars map to constant values} {old vars to new vars that should be equal}]"
  [sub-map]
  (reduce (fn [[newvars equalities] [oldvar value]]
    (let [v (gen-nullable-var)]
      [(assoc newvars v value) (assoc equalities oldvar v)]))
    [{} {}] (seq sub-map)))

(w/deffilterop non-null? [& objs]
  (every? (complement nil?) objs))

(defn- mk-insertion-assembly [subs]
  (if (not-empty subs)
    (apply w/insert (transpose (seq subs)))
    identity ))

(defn- replace-ignored-vars [vars]
  (map #(if (= "_" %) (gen-nullable-var) %) vars))

(defn- mk-null-check [fields]
  (let [non-null-fields (filter non-nullable-var? fields)]
    (if (not-empty non-null-fields)
      (non-null? non-null-fields)
      identity )))

(defmulti enhance-predicate (fn [pred & rest] (:type pred)))

(defn- identity-if-nil [a]
  (if a a identity))

(defmethod enhance-predicate :operation [pred infields inassem outfields outassem]
  (let [inassem (identity-if-nil inassem)
        outassem (identity-if-nil outassem)]
    (merge pred {:assembly (w/compose-straight-assemblies inassem (:assembly pred) outassem)
                 :outfields outfields
                 :infields infields})))

(defmethod enhance-predicate :aggregator [pred infields inassem outfields outassem]
  (let [inassem (identity-if-nil inassem)
        outassem (identity-if-nil outassem)]
    (merge pred {:pregroup-assembly (w/compose-straight-assemblies inassem (:pregroup-assembly pred))
                 :post-assembly (w/compose-straight-assemblies (:post-assembly pred) outassem
                                  ; work-around to cascading bug, TODO: remove when fixed in cascading
                                  (w/identity Fields/ALL :> Fields/RESULTS))
                 :outfields outfields
                 :infields infields})))

(defmethod enhance-predicate :generator [pred infields inassem outfields outassem]
  (when inassem
    (throw (RuntimeException. "Something went wrong in planner - generator received an input modifier")))
  (merge pred {:pipe (outassem (:pipe pred))
               :outfields outfields}))

(defn- fix-duplicate-infields
  "Workaround to Cascading not allowing same field multiple times as input to an operation.
   Copies values as a workaround"
  [infields]
  (let [update-fn (fn [[newfields dupvars assem] f]
                    (if ((set newfields) f)
                      (let [newfield (gen-nullable-var)
                            idassem (w/identity f :fn> newfield :> Fields/ALL)]
                        [(conj newfields newfield)
                         (conj dupvars newfield)
                         (w/compose-straight-assemblies assem idassem)])
                      [(conj newfields f) dupvars assem]))]
    (reduce update-fn [[] [] identity] infields)))

(defn mk-option-predicate [[op _ _ infields _]]
    (predicate option op infields))

(defn- mk-serializable-options
  "Hack until Clojure 1.2 where Clojure data is serializable"
  [options]
  (let [sortfields (:sort options)]
    (if sortfields
      (assoc options :sort (ArrayList. sortfields))
      options )))

(defn build-predicate
  "Build a predicate. Calls down to build-predicate-specific for predicate-specific building 
  and adds constant substitution and null checking of ? vars."
  [options op opvar hof-args orig-infields outfields]
    (let [options                        (mk-serializable-options options)
          outfields                      (replace-ignored-vars outfields)
          [infields infield-subs]        (variable-substitution orig-infields)
          [infields dupvars
            duplicate-assem]             (fix-duplicate-infields infields)
          [outfields outfield-subs]      (variable-substitution outfields)
          predicate                      (build-predicate-specific op opvar hof-args infields outfields options)
          [newsubs equalities]           (output-substitution outfield-subs)
          new-outfields                  (concat outfields (keys newsubs) (keys infield-subs) dupvars)
          in-insertion-assembly          (when-not (empty? infields) (w/compose-straight-assemblies
                                            (mk-insertion-assembly infield-subs)
                                            duplicate-assem))
          out-insertion-assembly         (mk-insertion-assembly newsubs)
          null-check-out                 (mk-null-check new-outfields)
          equality-assemblies            (map w/equal equalities)
          outassembly                    (apply w/compose-straight-assemblies
                                            (concat [out-insertion-assembly
                                                    null-check-out]
                                          equality-assemblies))]
          (enhance-predicate predicate
                             (filter cascalog-var? orig-infields)
                             in-insertion-assembly
                             new-outfields
                             outassembly)))
