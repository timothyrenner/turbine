(ns turbine.routes
    (:require [clojure.core.async :refer [<!! >!! thread alts!! chan]]))

;;;; Define the hierarchy.
(def route-hierarchy 
    (-> (make-hierarchy)
        (derive :scatter :fan-out)
        (derive :splatter :fan-out)
        (derive :select :fan-out)
        (derive :union :fan-in)
        (derive :gather :fan-in)))

(defmulti xform-aliases first :hierarchy #'route-hierarchy)

(defmethod xform-aliases :fan-out [route-spec]
    (into {} 
        (map (fn [v] [(first v) (second v)]) 
             (nth route-spec 2))))
                            
(defmethod xform-aliases :fan-in [route-spec]
    (into {} [(nth route-spec 2)]))

(defmethod xform-aliases :in [route-spec]
    (into {} [(subvec route-spec 1)]))

;;;; There are no aliases in a sinker, but they're in the spec, so this makes
;;;; all of the collection functions applied to the spec consistent.
(defmethod xform-aliases :sink [route-spec] {})

(defn- scatter!
    "Scatter the value of the in channel to out channels with the attached
     transducers.

    `in-chan` The input channel to read.

    `out-chans` The outbound channels.
    "
    [in-chan out-chans]
    (thread 
        (loop []
             (let [in-val (<!! in-chan)]
                 (doseq [out-chan out-chans]
                     (>!! out-chan in-val)))
             (recur))))

(defn- splatter!
    "Take a vector value from the input channel and splats its values across
     output channels with the attached transducers
     (e.g. a vector [1 2 3] will write 1 to the first output
     channel, 2 to the second, and 3 to the third). If the vector of input 
     values or output transducers is longer than the other the longer vector is 
     truncated (e.g. a vector [1 2 3] from the in channel will only write to the 
     three or fewer output channels depending on the length of `out-chans`).

    `in-chan` The input channel to receive the vector from.

    `out-chans` A vector of outbound channels.
    "
    [in-chan out-chans]
    (thread 
         (loop []
             ;; Read the sequence from the in-channel.
            (let [in-seq (<!! in-chan)]
                 ;; Write each element to it's corresponding out-chan.
                 (doseq [[out-chan out-val] (map vector out-chans in-seq)]
                    (>!! out-chan out-val)))
            (recur))))

(defn- select!
    "Takes a value from the in-channel and passes it to one or more output
     channels depending on the value of the selector.
   
    `in-chan` The input channel.
   
    `out-chans-with-selectors` A vector of pairs: 
    `[output-chan selector-value]`, passing the input value to any 
    channels for which `(= selector-value (selector-fn value))` is `true`.

    `selector-fn` The function applied to the incoming value which is
    then matched to the selector values attached to the outbound channels.
    "
    [in-chan out-chans-with-selectors selector-fn]
    (thread 
        (loop []
            ;; Read a single value from in-chan.
            (let [in-val (<!! in-chan)
                ;; Determine the selector value from selector-fn and in-val.
                in-selector-val (selector-fn in-val)]
                ;; Write in-val to output channels with a matching selector 
                ;; value.
                (doseq [[out-chan chan-selector-val] 
                        out-chans-with-selectors]
                    (when (= in-selector-val chan-selector-val)
                    (>!! out-chan in-val))))
            (recur))))

(defn- gather!
    "Take a value from each input channel and concatenate them into a vector.
    This route will block until a value from each input arrives.

    `in-chans` A sequence of input channels.

    `out-chan` The outbound channel.
    "
    [in-chans out-chan]
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
            (recur))))

(defn- union!
    "Take a value from any one of several input channels and writes it to the
    output channel.

    `in-chans` A sequence of input channels.

    `out-chan` The outbound channel.
    "
    [in-chans out-chan]
    (thread 
        (loop []
            ;; Read from any of the input channels.
            (let [[in-val _] (alts!! in-chans)]
                ;; Write the value to the output channel.
                (>!! out-chan in-val))
            (recur))))

(defn- sink!
    "Apply a callback to the input channel, terminating this leaf of the DAG.

    `in-chan` The input channel.

    `sink-fn` The function to call on the input value.
    "
    [in-chan sink-fn]
    (thread 
        (loop []
            (let [in (<!! in-chan)]
                (sink-fn in))
            (recur))))

(defn make-topology [spec]
    (let [xforms (into {} (map xform-aliases spec))
                 chans (into {} (zipmap (keys xforms) 
                                        (map #(chan 5 %) (vals xforms))))]
        ;; TODO:
        ;; Look into an extensible routing method to allow for custom routes.
        ;; Multimethod is probably simplest, with a macro wrapper to enclose
        ;; the route function in the threaded loop.
        ;; Route all of the specifiers except the inputs.
        (doseq [rspec (remove (fn [v] (= :in (first v))) spec)]
            
            (case (first rspec)
                
                :scatter (scatter! (chans (second rspec))
                                   (map (fn [o] (chans (first o))) 
                                                (nth rspec 2)))
                
                :splatter (splatter! (chans (second rspec))
                                     (map (fn [o] (chans (first o))) 
                                                  (nth rspec 2)))
                
                :select (select! (chans (second rspec))
                                 (map (fn [[o _ v]] [(chans o) v]) 
                                      (nth rspec 2))
                                      (nth rspec 3))
                
                :union (union! (map chans (second rspec))
                               (chans (first (nth rspec 2))))

                :gather (gather! (map chans (second rspec))
                                 (chans (first (nth rspec 2))))

                :sink (sink! (chans (second rspec)) (nth rspec 2))))

        ;; Now grab all of the inputs and put them in the entry point functions.
        (map (fn [a] 
                (let [c (chans (second a))]
                    #(>!! c  %)))
             (filter (fn [s] (= (first s) :in)) spec))))
