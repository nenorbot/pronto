(ns pronto.emitters
  (:require [pronto.type-gen :as t]
            [pronto.utils :as u]))

(defn emit-assoc [fields builder k v]
  `(case ~k
     ~@(interleave
         (map :kw fields)
         (map #(t/gen-setter (:type-gen %) builder k v) fields))
     (throw (IllegalArgumentException. (str "cannot assoc " ~k)))))


(defn emit-val-at [fields obj k]
  `(case ~k

     ~@(interleave
         (map :kw fields)
         (map #(t/gen-getter (:type-gen %) obj k) fields))

     nil))


(defn static-call [^Class class method-name]
  (symbol (str (.getName class) "/" method-name)))


(defn get-builder-class [^Class clazz]
  (.getReturnType (.getDeclaredMethod clazz "toBuilder" (make-array Class 0))))


(defn emit-deftype [^Class clazz]
  (let [fields              (t/get-fields clazz)
        o                   (u/with-type-hint (gensym 'o) clazz)
        builder-class       (get-builder-class clazz)
        wrapper-class-name  (u/class-name->wrapper-class-name clazz)
        transient-ctor-name (u/transient-ctor-name clazz)]
    `(deftype ~wrapper-class-name [~o]

       java.io.Serializable

       pronto.proto/ProtoMap

       (~'get-proto [this#] ~o)

       clojure.lang.Associative

       ~(let [this (gensym 'this)
              k    (gensym 'k)
              v    (gensym 'v)
              b    (u/with-type-hint (gensym 'builder) builder-class)]
          `(~'assoc [~this ~k ~v]
            (let [~b (.toBuilder ~o)]
              ~(emit-assoc fields b k v)
              (new ~wrapper-class-name (.build ~b)))))


       (containsKey [this# k#]
         (boolean (get ~(into #{} (map :kw fields))
                       k#)))

       (entryAt [this# k#]
         (clojure.lang.MapEntry/create k# (.valAt this# k#)))

       clojure.lang.MapEquivalence
       clojure.lang.IPersistentMap

       (without [this# k#]
         (throw (UnsupportedOperationException. "cannot dissoc from a proto map")))

       clojure.lang.ILookup

       ~(let [k (gensym 'k)]
          `(valAt [_ ~k]
                  ~(emit-val-at fields o k)))

       (valAt [this# k# not-found#]
         (.valAt this# k#))


       clojure.lang.IPersistentCollection

       (cons [this# o#]
         (pronto.PersistentMapHelpers/cons this# o#))

       (empty [this#]
         (new ~wrapper-class-name
              (.build (~(static-call clazz "newBuilder")))))

       (count [this#] ~(count fields))

       (equiv [this# other#]
         (pronto.PersistentMapHelpers/equiv this#
                                               (if (instance? ~clazz other#)
                                                 (~(u/proto-ctor-name clazz) other#)
                                                 other#)))

       clojure.lang.Seqable

       ~(let [this (gensym 'this)]
          `(seq [~this]
                (clojure.lang.ArraySeq/create
                  (object-array
                    [~@(map (fn [fd]
                              `(.entryAt ~this ~(:kw fd)))
                            fields)]))))

       pronto.DefaultingFn

       (invoke [this# arg1#]
         (pronto.PersistentMapHelpers/invoke this# arg1#))

       (invoke [this# arg1# not-found#]
         (pronto.PersistentMapHelpers/invoke this# arg1# not-found#))

       clojure.lang.IEditableCollection

       ;; TODO: clean this up
       (asTransient [this#]
         (~transient-ctor-name (.toBuilder ~o) true))

       java.lang.Iterable

       ~(let [this (gensym 'this)]
          `(iterator [~this]
                     (clojure.lang.ArrayIter/create
                       (object-array
                         [~@(map (fn [fd]
                                   `(.entryAt ~this ~(:kw fd)))
                                 fields)]))))

       java.util.Map

       (clear [this#] (throw (UnsupportedOperationException.)))

       (containsValue [this# value#]
         (pronto.PersistentMapHelpers/containsValue this# value#))

       (entrySet [this#]
         (pronto.PersistentMapHelpers/entrySet this#))

       (keySet [this#]
         (pronto.PersistentMapHelpers/keySet this#))

       (values [this#]
         (pronto.PersistentMapHelpers/values this#))

       (get [this# key#]
         (pronto.PersistentMapHelpers/get this# key#))

       (isEmpty [this#]
         (pronto.PersistentMapHelpers/isEmpty this#))

       (put [this# k# v#] (throw (UnsupportedOperationException.)))

       (putAll [this# m#] (throw (UnsupportedOperationException.)))

       (remove [this# k#] (throw (UnsupportedOperationException.)))

       (size [this#] (.count this#))

       Object

       (toString [this#]
         (pronto.PersistentMapHelpers/toString this#))

       (equals [this# obj#]
         (pronto.PersistentMapHelpers/equals this# obj#)))))


(defn check-editable! [editable?]
  (when-not editable?
    (throw (IllegalAccessError. "Transient used after persistent! call"))))

(defn emit-transient [^Class clazz]
  (let [fields                       (t/get-fields clazz)
        builder-class                (get-builder-class clazz)
        o                            (u/with-type-hint 'o builder-class)
        transient-wrapper-class-name (u/class-name->transient-class-name clazz)]
    `(deftype ~transient-wrapper-class-name [~o ~(with-meta 'editable? {:unsynchronized-mutable true})]

       java.io.Serializable

       pronto.proto/ProtoMap

       (~'get-proto [this#] ~o)

       clojure.lang.ITransientMap

       ~(let [this (gensym 'this)
              k    (gensym 'k)
              v    (gensym 'v)]
          `(~'assoc [~this ~k ~v]
            (check-editable! ~'editable?)
            ~(emit-assoc fields o k v)
            ~this))

       (without [this# k#]
         (check-editable!)
         (throw (UnsupportedOperationException. "cannot dissoc from a proto map")))

       (persistent [this#]
         (set! ~'editable? false)
         (~(u/proto-ctor-name clazz) (.build ~o)))

       (count [this#]
         (check-editable! ~'editable?)
         ~(count fields))

       clojure.lang.ITransientAssociative2

       (containsKey [this# k#]
         (check-editable! ~'editable?)
         (boolean (get ~(into #{} (map :kw fields))
                       k#)))

       (entryAt [this# k#]
         (clojure.lang.MapEntry/create k# (.valAt this# k#)))


       pronto.DefaultingFn

       (invoke [this# arg1#]
         (check-editable!)
         (.valAt this# arg1#))

       (invoke [this# arg1# not-found#]
         (check-editable!)
         (.valAt this# arg1# not-found#))

       clojure.lang.ITransientCollection

       (conj [this# val#]
         (check-editable!)
         (pronto.TransientMapHelpers/conj this# val#))

       clojure.lang.ILookup

       ~(let [k (gensym 'k)]
          `(valAt [this# ~k]
                  (check-editable! ~'editable?)
                  ~(emit-val-at fields o k)))

       (valAt [this# k# not-found#]
         (.valAt this# k#)))))


(defn emit-proto-ctor [clazz]
  (let [wrapper-class-name (u/class-name->wrapper-class-name clazz)
        fn-name            (u/proto-ctor-name clazz)]
    `(def ~fn-name
       (fn
         [o#]
         (if(instance? ~clazz o#)
           (new ~wrapper-class-name o#)
           (throw (IllegalArgumentException. (str "cannot wrap " (or (class o#) "nil")))))))))

(defn emit-map-ctor [clazz]
  (let [wrapper-class-name (u/class-name->wrapper-class-name clazz)
        fn-name            (u/map-ctor-name clazz)]
    `(def ~fn-name
       (fn
         ([] (~fn-name {}))
         ([o#]
          (if (map? o#)
            (let [res# (new ~wrapper-class-name (.build (~(static-call clazz "newBuilder"))))]
              (reduce (fn [acc# [k# v#]]
                        (assoc acc# k# v#))
                      res#
                      o#))
            (throw (IllegalArgumentException. (str "cannot wrap " (or (class o#) "nil"))))))))))

