(ns slifin.learning
  (:require
    [com.rpl.rama :refer :all]
    [com.rpl.rama.ops :as ops]
    [clojure.test :refer [deftest is testing]]))


;(?<-
;  (str "hello world" :> *str)
;  (println *str))
;
;
;(?<-
;  (+ 1 2 :> *a)
;  (* *a 10 :> *b)
;  (println *a *b))


(deframaop emit-many-times []
  (:> 1)
  (:> 3)
  (:> 2)
  (:> 5))

(?<-
  (emit-many-times :> *v)
  (println "Emitted:" *v))


