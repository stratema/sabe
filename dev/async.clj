(ns async
  (:require [clojure.core.async :refer :all]))

;;;; Mult ;;;;

;; Create a mult. This allows data from one channel to be broadcast
;; to many other channels that "tap" the mult.

(def to-mult (chan 1))
(def m (mult to-mult))

(let [c (chan 1)]
  (tap m c)
  (go (loop []
        (when-let [v (<! c)]
          (println "Got! " v)
          (recur)))))

(>!! to-mult 42)
(>!! to-mult 43)

(close! to-mult)


;;;; Pub/Sub ;;;

;; This is a bit like Mult + Multimethods

(def to-pub (chan 1))
(def p (pub to-pub :tag))

(def print-chan (chan 1))

(go (loop []
      (when-let [v (<! print-chan)]
        (println v)
        (recur))))

;; This guy likes updates about cats.
(let [c (chan 1)]
  (sub p :cats c)
  (go (println "I like cats:")
      (loop []
        (when-let [v (<! c)]
          (>! print-chan (pr-str "Cat guy got: " v))
          (recur)))))

;; This guy likes updates about dogs
(let [c (chan 1)]
  (sub p :dogs c)
  (go (println "I like dogs:")
      (loop []
        (when-let [v (<! c)]
          (>! print-chan (pr-str "Dog guy got: " v))
          (recur)))))

;; This guy likes updates about animals
(let [c (chan 1)]
  (sub p :dogs c)
  (sub p :cats c)
  (go (println "I like cats or dogs:")
      (loop []
        (when-let [v (<! c)]
          (>! print-chan (pr-str "Cat/Dog guy got: " v))
          (recur)))))


(defn send-with-tags [msg]
  (doseq [tag (:tags msg)]
    (println "sending... " tag)
    (>!! to-pub {:tag tag
                 :msg (:msg msg)})))

(send-with-tags {:msg "New Cat Story"
                 :tags [:cats]})

(send-with-tags {:msg "New Dog Story"
                 :tags [:dogs]})

(send-with-tags {:msg "New Pet Story"
                 :tags [:cats :dogs]})


(close! to-pub)
