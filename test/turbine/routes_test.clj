(ns turbine.routes-test
	(:require [clojure.core.async :refer [<!! >!! chan]]
			  [turbine.routes :refer :all]
			  [clojure.test :refer :all]))

(def single-!-xform (map #(str % "!")))
(def double-!-xform (map #(str % "!!")))

(deftest xform-aliases-test
	
	(testing "Properly extracts aliases from a fan-out route specifier."
		(is (= {:out1 :xform1 :out2 :xform2}
			   (xform-aliases [:scatter :in1 [[:out1 :xform1]
					 						  [:out2 :xform2]]]))))
	
	(testing "Properly extracts aliases from a fan-in route specifier."
		(is (= {:out1 :xform1}
			   (xform-aliases [:union [:in1 :in2] [:out1 :xform1]]))))
	
	(testing "Properly extracts aliases from a sink route specifier."
		(is (= {} (xform-aliases [:sink :out1 :println]))))
	
	(testing "Properly extracts aliases from an input route specifier."
		(is (= {:in1 :xform1}
			   (xform-aliases [:in :in1 :xform1])))))

(deftest make-topology-test

	(testing "Properly creates topology with a scatter route."
		(let [output-chan (chan 5) ; Landing point for the output.
					scatter-in ; Make the topology, defined as it's input function.
					(first 
						(make-topology 
							[[:in :in1 (map identity)]
							 [:scatter :in1 [[:out1 single-!-xform]
							 			     [:out2 double-!-xform]]]
							 [:sink :out1 (fn [v] (>!! output-chan v))]
							 [:sink :out2 (fn [v] (>!! output-chan v))]]))]
			;; Feed the input value into the topology.
			(scatter-in "hello")
			;; Read off the output channel and compare. Use a set so order is
			;; ignored.
			(let [answer (into #{} [(<!! output-chan) (<!! output-chan)])
				  truth #{"hello!" "hello!!"}]
				(is (= truth answer)))))

	(testing "Properly creates topology with a splatter route."
		(let [output-chan (chan 5) ; Landing point for the output.
			  splatter-in ; Make the topology, defined as it's input function.
			  (first 
			  	(make-topology 
					[[:in :in1 (map identity)]
					 [:splatter :in1 [[:out1 single-!-xform]
					 				  [:out2 double-!-xform]]]
					 [:sink :out1 (fn [v] (>!! output-chan v))]
					 [:sink :out2 (fn [v] (>!! output-chan v))]]))]
			;; Feed the input value into the topology.
			(splatter-in ["Hi" "there"])
			;; Read off the output channel and compare. Use a set so order is 
			;; ignored.
			(let [answer (into #{} [(<!! output-chan) (<!! output-chan)])
				  truth #{"Hi!" "there!!"}]
				(is (= truth answer)))))

	(testing "Properly creates topology with a select route."
		(let [output-chan (chan 5) ; Landing point for the output.
			  select-in ; Make the topology, defined as it's input function.
				(first 
					(make-topology
						[[:in :in1 (map identity)]
						 [:select :in1 [[:out1 single-!-xform true]
						 				[:out2 double-!-xform false]]
							(fn [x] (Character/isLowerCase (first x)))]
						 [:sink :out1 (fn [v] (>!! output-chan v))]
						 [:sink :out2 (fn [v] (>!! output-chan v))]]))]
	  		;; Feed the input values into the topology.
			(select-in "hello")
			(select-in "There")
			;; Read off the output channel and compare. Use a set so order is 
			;; ignored.
			(let [answer (into #{} [(<!! output-chan) (<!! output-chan)])
				  truth #{"hello!" "There!!"}]
					(is (= truth answer)))))

	(testing "Properly creates topology with a union route."
		(let [output-chan (chan 5) ; Landing point for the output.
			  union-in ; Make the topology, defined as it's input function.
				(first 
					(make-topology
						[[:in :in1 (map identity)]
						 [:scatter :in1 [[:exc1 single-!-xform]
						 				 [:exc2 double-!-xform]]]
						 [:union [:exc1 :exc2] [:ident (map identity)]]
						 [:sink :ident (fn [v] (>!! output-chan v))]]))]
	  	;; Feed the input value into the topology.
		(union-in "Hello")
		;; Read off the output channel and compare. Use a set so order is 
		;; ignored.
		(let [answer (into #{} [(<!! output-chan) (<!! output-chan)])
			  truth #{"Hello!" "Hello!!"}]
			(is (= truth answer))))))
	
	(testing "Properly creates topology with a gather route."
		(let [output-chan (chan 5) ; Landing point for the output.
			  gather-in ; Make the topology, defined as it's input function.
			  	(first
					(make-topology
						[[:in :in1 (map identity)]
						 [:scatter :in1 [[:exc1 single-!-xform]
						 				 [:exc2 double-!-xform]]]
						 [:gather [:exc1 :exc2] [:ident (map identity)]]
						 [:sink :ident (fn [v] (>!! output-chan v))]]))]
			;; Feed the input value into the topology.
			(gather-in "Hello")
			;; Read off the output channel and compare.
			(let [answer (<!! output-chan)
				  truth ["Hello!" "Hello!!"]]
				(is (= truth answer)))))