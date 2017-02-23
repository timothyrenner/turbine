(ns turbine.routes
    (:require [clojure.core.async :refer [<!! >!! thread alts!! chan]]))

(defmulti xform-aliases first)

(defn- fan-out [route-spec]
    (into {}
        (map (fn [v] [(first v) (second v)])
             (nth route-spec 2))))

(defn- fan-in [route-spec]
    (into {} [(nth route-spec 2)]))

(defmethod xform-aliases :scatter [route-spec]
    (fan-out route-spec))

(defmethod xform-aliases :splatter [route-spec]
    (fan-out route-spec))

(defmethod xform-aliases :select [route-spec]
    (fan-out route-spec))

(defmethod xform-aliases :spread [route-spec]
    (fan-out route-spec))

(defmethod xform-aliases :union [route-spec]
    (fan-in route-spec))

(defmethod xform-aliases :gather [route-spec]
    (fan-in route-spec))

(defmethod xform-aliases :in [route-spec]
    (into {} [(subvec route-spec 1)]))

;;;; There are no aliases in a sinker, but they're in the spec, so this makes
;;;; all of the collection functions applied to the spec consistent.
(defmethod xform-aliases :sink [route-spec] {})

(defmulti make-route 
    "All methods take two arguments, `route-spec`, which is the route specifier, and
    `chans`, which is a map of channel aliases to the channels themselves.
    
    The structure of the route specifier depends on the type of route itself -
    consult the documentation for details.
    "
    (fn [route-spec chans] (first route-spec)))

(defmethod make-route :scatter
    [route-spec chans]
    (let [in-chan (chans (second route-spec))
         ;; The outbound channel aliases are the first elements of the
         ;; third part of the route specifier.
          out-chans (map (fn [o] (chans (first o)))
                         (nth route-spec 2))]
        (thread 
            (loop []
                 (let [in-val (<!! in-chan)]
                     (doseq [out-chan out-chans]
                         (>!! out-chan in-val)))
                (recur)))))

(defmethod make-route :splatter
    [route-spec chans]
    (let [in-chan (chans (second route-spec))
          ;; The outbound channel aliases are the first elements of the 
          ;; third part of the route specifier.
          out-chans (map (fn [o] (chans (first o)))
                         (nth route-spec 2))]
        (thread 
            (loop []
                ;; Read the sequence from the in-channel.
                (let [in-seq (<!! in-chan)]
                    ;; Write each element to it's corresponding out-chan.
                    (doseq [[out-chan out-val] (map vector out-chans in-seq)]
                        (>!! out-chan out-val)))
            (recur)))))

(defmethod make-route :select
    [route-spec chans]
    (let [in-chan (chans (second route-spec))
          out-chans-with-selectors
            ;; We need the out-channel alias (o) and the selector value (v).
            ;; The middle element is the xform, which we don't need. 
            (map (fn [[o _ v]] [(chans o) v])
                 (nth route-spec 2))
          selector-fn (nth route-spec 3)]
        (thread 
            (loop []
                ;; Read a single value from in-chan.
                (let [in-val (<!! in-chan)
                      ;; Determine the selector value from selector-fn and 
                      ;; in-val.
                      in-selector-val (selector-fn in-val)]
                    ;; Write in-val to output channels with a matching selector 
                    ;; value.
                    (doseq [[out-chan chan-selector-val] 
                             out-chans-with-selectors]
                        (when (= in-selector-val chan-selector-val)
                              (>!! out-chan in-val))))
                (recur)))))

(defmethod make-route :spread
    [route-spec chans]
    (let [in-chan (chans (second route-spec))
          ;; Create an infinite (lazy) sequence of out channels to use in the
          ;; loop construct.
          out-chans (cycle (map (fn [o] (chans (first o)))
                           (nth route-spec 2)))]
        (thread
            ;; The loop is initialized with the full out-chans sequence.
            (loop [out-chan-cycle out-chans]
                (let [in-val (<!! in-chan)]
                    ;; Drop the input value onto whatever channel is first in
                    ;; the cycle.
                    (>!! (first out-chan-cycle) in-val))
                ;; Advance the loop by cycling to the next channel.
                (recur (next out-chan-cycle))))))

(defmethod make-route :gather
    [route-spec chans]
    (let [in-chans (map chans (second route-spec))
          out-chan (chans (first (nth route-spec 2)))]
        (thread
            (loop []
                ;; Read each value from in-chan.
                (->> 
                    (for [in-chan in-chans]
                        (<!! in-chan))
                    ;; Convert the values from a seq into a vector.
                    vec
                    ;; Write that vector to the output channel
                    (>!! out-chan))
                (recur)))))

(defmethod make-route :union
    [route-spec chans]
    (let [in-chans (map chans (second route-spec))
          out-chan (chans (first (nth route-spec 2)))]
        (thread 
            (loop []
                ;; Read from any of the input channels.
                (let [[in-val _] (alts!! in-chans)]
                    ;; Write the value to the output channel.
                    (>!! out-chan in-val))
                (recur)))))

(defmethod make-route :sink
    [route-spec chans]
    (let [in-chan (chans (second route-spec))
          sink-fn (nth route-spec 2)]
        (thread 
            (loop []
                (let [in (<!! in-chan)]
                    (sink-fn in))
                (recur)))))


