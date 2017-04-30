(ns turbine.routes-test
	(:require [clojure.core.async :refer [<!! >!! chan]]
			  [turbine.routes :refer :all]
			  [turbine.core :refer :all]
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
				(is (= truth answer)))
			;; Close the topology to validate the route closer.
			;; NOTE: This test condition is not fully correct - we don't know
			;; if we closed _all_ of the channels in the topology.
			(close-topology [scatter-in])
			(is (= false (scatter-in "hi")))))

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
				(is (= truth answer)))
			;; Close the topology to validate the route's closer logic.
			;; NOTE: This test condition is incomplete - it does not test that
			;; _all_ of the channels in the topology closed.
			(close-topology [splatter-in])
			(is (= false (splatter-in ["hi" "there"])))))

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
					(is (= truth answer)))
			;; Close the topology to validate the route's close logic.
			;; NOTE: This test condition is incomplete - it doesn't test that
			;; _all_ of the channels in the topology close.
			(close-topology [select-in])
			(is (= false (select-in "Hello")))))

	(testing "Properly creates a topology with a spread route."
		(let [output-chan (chan 5) ; Landing point for the output.
			  spread-in ; Make the topology.
			  	(first
				  	(make-topology
					  	[[:in :in1 (map identity)]
						 [:spread :in1 [[:out1 single-!-xform]
						 				[:out2 double-!-xform]]]
						 [:sink :out1 (fn [v] (>!! output-chan v))]
						 [:sink :out2 (fn [v] (>!! output-chan v))]]))]
			;; Feed the input values into the topology.
			(spread-in "hello")
			(is (= (<!! output-chan) "hello!"))

			(spread-in "hello")
			(is (= (<!! output-chan) "hello!!"))

			(spread-in "there")
			(is (= (<!! output-chan) "there!"))

			(spread-in "there")
			(is (= (<!! output-chan) "there!!"))
			
			;; Close the topology to validate the route's closer logic.
			;; NOTE: This test condition is not complete - it doesn't test that
			;; _all_ of the channels in the topology closed.
			(close-topology [spread-in])
			(is (= false (spread-in "hi")))))

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
				(is (= truth answer)))
			;; Close the topology to validate the route's closer logic.
			;; NOTE: This test condition is incomplete - it does not test that
			;; _all_ of the channels in the topology have closed.
			(close-topology [union-in])
			(is (= false (union-in "hello")))))
	
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
				(is (= truth answer)))
			;; Close the topology to validate the route's closer logic.
			;; NOTE: This test condition is incomplete - it does not test that
			;; _all_ of the channels in the topology have closed.
			(close-topology [gather-in])
			(is (= false (gather-in "hello"))))))