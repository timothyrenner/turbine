(ns turbine.core
    (:require [turbine.routes :refer [xform-aliases make-route]]
              [clojure.core.async :refer [chan >!! close!]]))

(defn clone-kw [n kw]
    """ Clones a keyword into a vector of keywords with different integer 
        suffixes.
    """
    (apply vector 
        (map (fn [kw x] (-> kw name (str x) keyword)) (repeat kw) (range n))))

(defmacro clone-channel [n alias transducer & rest]
    "Clones a channel specifier, assigning each channel a new alias based on the
     alias of the root channel alias. The channel alias must be a keyword or a 
     string, and the resulting aliases will be keywords.
     For example,

     (clone-channel 2 :exc (map #(str % \"!\")] )
     => [[:exc0 (map #(str % \"!\")]
         [:exc1 (map #(str % \"!\")]]
    "
    ;; The unquote-splice splats the "for" list.
    `[ ~@(for [x (range n)]
            ;; apply vector is the standard way of bringing a seq into a vector.
            ;; This merges the additional elements in rest with the new vector.
            (apply vector (-> alias name (str x) keyword) transducer rest))])

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

(defn close-topology [input-fns]
    """ Closes out the topology. In-flight messages will complete before 
        channels are closed.

        `input-fns` The input functions for the topology. If this is not all of
        the functions that define the topology there won't be an error, but it
        won't fully close.
    """
    (doseq [input-fn input-fns]
        (input-fn ::close)))