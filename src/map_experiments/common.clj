(ns map-experiments.common
  (:require [map-experiments.smart-maps.protocol :refer :all])
  (:import [clojure.lang
            IPersistentMap IPersistentSet]))

; Functions used by many of the files...

(prefer-method print-method
  IPersistentMap IPersistentSet)

(defn transientize
  "If x is nil or of the same type as empty-value, returns a transient version of x. Otherwise, (e.g. if x is already transient) returns x."
  ([empty-value x]
   (cond (nil? x) (transient empty-value)
         (instance? (type empty-value) x) (transient x)
         :else x)))

(defn persistentize
  "If x is nil or of the same type as empty-value, returns a persistent version of x. Otherwise, (e.g. if x is already persistent) returns x."
  ([empty-value x]
   (cond (nil? x) (persistent empty-value)
         (instance? (type empty-value) x) (persistent x)
         :else x)))

(defn rdissoc
  "Dissociates every key mapped to any value in vs. Works only with things implementing the Invertible protocol."
  ([coll & vs]
   (inverse (apply dissoc (inverse coll) vs))))

(defn specific
  "Wraps a function returning a collection so that it will return nil for an empty collection, the single element contained for a singleton collection, and will throw an error for a collection with more than one element."
  ([function]
   (fn [& args]
     (when-let [result (apply function args)]
               (if (not (seq (rest result)))
                   (first result)
                   (throw (IllegalArgumentException.
                            (str "Violation of 'specific' constraint on function <" function ">. The function, when given argument(s) " args ", returned a collection with more than one element: " (with-out-str (pr result)) "."))))))))

