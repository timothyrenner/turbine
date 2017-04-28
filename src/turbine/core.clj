(ns turbine.core
    (:require [turbine.routes :refer [xform-aliases make-route]]
              [clojure.core.async :refer [chan >!! close!]]))

;;;; TODO: UNIT TEST.
(defmacro clone-channel [[alias transducer & rest] n]
    "Clones a channel specifier, assigning each channel a new alias based on the
     alias of the root channel alias. 
     For example,

     (clone-channel [:exc-1 (map #(str % \"!\")] 2)
     => [[:exc-10 (map #(str % \"!\")]
         [:exc-11 (map #(str % \"!\")]]
    "
    ;; The unquote-splice splats the "for" list.
    `[ ~@(for [x (range n)]
            ;; apply vector is the standard way of bringing a seq into a vector.
            ;; This merges the additional elements in rest with the new vector.
            (apply vector (-> alias name (str x) keyword) transducer rest))])

;;;; TODO: Unit test.
(defn- make-in [input-chan]
    (fn [v]
        (if-not 
            ;; Use a namespaced keyword as the sentinel so only way to apply
            ;; this is through functions in this namespace.
            (= v ::close)
            (>!! input-chan v)
            (close! input-chan))))

(defn make-topology 
    "Builds the topology from the provided specifier.

    `spec` The topology specifier, as a vector of route specifiers.

    There are six built-in route specifiers. These are their general forms:

    ```clojure
    [:scatter in-alias [out-specifier1 out-specifier2 ...]]
    [:union [in-alias1 in-alias2 ...] out-specifier]
    [:gather [in-alias1 in-alias2 ...] out-specifier]
    [:select in-alias 
             [out-specifier-and-selector1
              out-specifier-and-selector2 ...]
             selector-fn]
    [:spread in-alias [out-specifier1 out-specifier2 ...]]
    [:splatter in-alias [out-specifier1 out-specifier2 ...]]
    [:in in-specifier]
    [:sink in-alias sink-fn]

    Consult the documentation for specifics on what these routes do.
    ```
    "
    [spec]
    (let [xforms (into {} (map xform-aliases spec))
          chans (into {} (zipmap (keys xforms) 
                                 (map #(chan 5 %) (vals xforms))))]
        ;; Route all of the specifiers except the inputs.
        (doseq [rspec (remove (fn [v] (= :in (first v))) spec)]
            (make-route rspec chans))
        ;; Now grab all of the inputs and put them in the entry point functions.
        (->> (filter (fn [s] (= (first s) :in)) spec)
             ;; Grab the second element (the channel alias).
             (map second)
             ;; Extract the actual channel.
             (map chans)
             ;; Make the "in" function.
             (map make-in))))