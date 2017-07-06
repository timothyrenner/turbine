(ns turbine.routes-test
	(:require [clojure.core.async :refer [<!! >!! chan]]
			  [turbine.routes :refer :all]
			  [turbine.core :refer :all]
			  [clojure.test :refer :all]))

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

