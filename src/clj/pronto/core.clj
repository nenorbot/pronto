(ns pronto.core
  (:require [pronto.wrapper :as w]
            [pronto.emitters :as e]
            [pronto.type-gen :as t]
            [pronto.transformations :as transform]
            [pronto.utils :as u]
            [pronto.protos]
            [pronto.runtime :as r]
            [pronto.reflection :as reflect]
            [clojure.walk :refer [macroexpand-all]]
            [potemkin])
  (:import [pronto ProtoMap]
           [com.google.protobuf Message GeneratedMessageV3
            Descriptors$FieldDescriptor
            Descriptors$Descriptor ByteString]))

(def ^:dynamic *instrument?* false)

(def ^:private global-ns "pronto.protos")

(def ^:private default-values #{0 0.0 nil "" false {} [] (byte-array 0) ByteString/EMPTY})
(def remove-default-values-xf
  (remove (fn [[_ v]] (contains? default-values v))))

(defn- resolve-class [class-sym]
  (let [clazz (if (class? class-sym) class-sym (resolve class-sym))]
    (when-not clazz
      (throw (IllegalArgumentException. (str "Cannot resolve \"" class-sym "\". Did you forget to import it?"))))
    (when-not (instance? Class clazz)
      (throw (IllegalArgumentException. (str class-sym " is not a class"))))
    (when-not (.isAssignableFrom Message ^Class clazz)
      (throw (IllegalArgumentException. (str clazz " is not a protobuf class"))))
    clazz))



(defn- resolve-loaded-class-safely [class-sym]
  (let [clazz (resolve-class class-sym)]
    (try
      (Class/forName (str (.replace ^String global-ns "-" "_") "." (u/class->map-class-name clazz)))
      clazz
      (catch ClassNotFoundException _))))

(defn- resolve-loaded-class [class-sym]
  (if-let [c (resolve-loaded-class-safely class-sym)]
    c
    (throw (IllegalArgumentException. (str class-sym " not loaded")))))


(defn disable-instrumentation! []
  (alter-var-root #'*instrument?* (constantly false)))


(defn enable-instrumentation! []
  (alter-var-root #'*instrument?* (constantly true)))


(defn proto-map->proto
  "Returns the protobuf instance associated with the proto-map"
  [^ProtoMap m]
  (.pmap_getProto m))


(defn has-field? [^ProtoMap m k]
  (.pmap_hasField m k))


(defn which-one-of [^ProtoMap m k]
  (.pmap_whichOneOf m k))


(defn one-of [^ProtoMap m k]
  (when-let [k' (which-one-of m k)]
    (get m k')))


(defmacro proto-map [clazz & kvs]
  {:pre [(even? (count kvs))]}
  (let [clazz (resolve-loaded-class clazz)]
    (if (empty? kvs)
      (symbol global-ns (str (e/empty-map-var-name clazz)))
      (let [chain (map (fn [[k v]] `(assoc ~k ~v)) (partition 2 kvs))]
        `(r/p-> ~(e/emit-default-transient-ctor clazz global-ns)
                ~@chain)))))


(defn proto-map? [m]
  (instance? ProtoMap m))


(defmacro clj-map->proto-map [clazz m]
  (let [clazz (resolve-loaded-class clazz)]
    `(transform/map->proto-map
       ~(e/emit-default-transient-ctor clazz global-ns)
       ~m)))

(defn proto->proto-map [^GeneratedMessageV3 proto]
  (e/proto->proto-map proto))


(defn proto-map->clj-map
  ([proto-map] (proto-map->clj-map proto-map (map identity)))
  ([proto-map xform]
   (let [mapper (map (fn [[k v]]
                       [k (cond
                            (proto-map? v) (proto-map->clj-map v xform)
                            (coll? v)      (let [fst (first v)]
                                             (if (proto-map? fst)
                                               (into []
                                                     (map #(proto-map->clj-map  % xform))
                                                     v)
                                               v))
                            :else          v)]))
         xform  (comp mapper xform)]
     (into {}
           xform
           proto-map))))

(defmacro bytes->proto-map [^Class clazz ^bytes bytes]
  (let [clazz (resolve-loaded-class clazz)
        bytea (with-meta bytes {:tag "[B"})]
    `(~(symbol
         global-ns
         (str '-> (u/class->map-class-name clazz)))
      (~(u/static-call clazz "parseFrom")
       ~bytea) nil)))

(defn byte-mapper [^Class clazz]
  (let [csym (symbol (.getName clazz))]
    (eval
      `(do
         (defproto ~csym)
         (fn [bytes#]
           (bytes->proto-map ~csym bytes#))))))


(defn proto-map->bytes [proto-map]
  (.toByteArray ^GeneratedMessageV3 (proto-map->proto proto-map)))

(defn- resolve-deps
  ([^Class clazz ctx] (first (resolve-deps clazz #{} ctx)))
  ([^Class clazz seen-classes ctx]
   (let [fields       (t/get-fields clazz ctx)
         deps-classes (->> fields
                           (map #(t/get-class (:type-gen %)))
                           (filter (fn [^Class clazz]
                                     (and (not (.isEnum clazz))
                                          (not (w/protobuf-scalar? clazz))))))
         seen-classes (conj seen-classes clazz)]
     (reduce (fn [[deps seen :as acc] dep-class]
               (if (get seen dep-class)
                 acc
                 (let [new-deps (conj deps dep-class)
                       [x y]    (resolve-deps dep-class seen-classes ctx)]
                   [(into new-deps x) y])))
             [[] seen-classes]
             deps-classes))))


(defn- update' [m k f]
  (if-let [v (get m k)]
    (assoc m k (f v))
    m))

(defn- resolve' [s]
  (if (symbol? s)
    (resolve s)
    s))


(defn- type-info [^Class clazz]
  (cond
    (reflect/enum? clazz) (into #{} (map str) (reflect/enum-values clazz))
    :else                 clazz))


(defn- field-schema [^Class clazz ^Descriptors$FieldDescriptor descriptor]
  (cond
    (.isMapField descriptor) (let [{:keys [key-type val-type]} (t/map-type-info clazz descriptor)]
                               {(type-info key-type)
                                (type-info val-type)})
    (.isRepeated descriptor) [(type-info (t/repeated-type-info clazz descriptor))]
    :else                    (let [^Class x (t/field-type clazz descriptor)]
                               (type-info x))))


(defn- find-descriptors [clazz descriptors ks]
  (loop [clazz       clazz
         descriptors descriptors
         ks          ks]
    (if-not (seq ks)
      [clazz descriptors]
      (let [[k & ks] ks
            ^Descriptors$FieldDescriptor descriptor
            (some
              (fn [^Descriptors$FieldDescriptor d]
                (when (= (name k) (.getName d))
                  d))
              descriptors)]
        (when descriptor
          (let [sub-descs (.getFields ^Descriptors$Descriptor (.getMessageType descriptor))
                clazz     (t/field-type clazz descriptor)]
            (recur clazz sub-descs ks)))))))


(defn schema [proto-map-or-class & ks]
  (let [clazz               (cond
                              (class? proto-map-or-class)     proto-map-or-class
                              (proto-map? proto-map-or-class) (class (proto-map->proto proto-map-or-class)))
        [clazz descriptors] (find-descriptors
                              clazz
                              (map :fd (t/get-fields clazz {}))
                              ks)]
    (when descriptors
      (into {}
            (map
              (fn [^Descriptors$FieldDescriptor fd]
                [(keyword
                   (when-let [oneof (.getContainingOneof fd)]
                     (.getName oneof))
                   (.getName fd))
                 (field-schema clazz fd)]))
            descriptors))))


(defn- init-ctx [opts]
  (merge
    {:key-name-fn   identity
     :enum-value-fn identity
     :iter-xf       nil
     :ns            "pronto.protos"
     :instrument?   *instrument?*}
    (-> (apply hash-map opts)
        (update' :key-name-fn eval)
        (update' :enum-value-fn eval)
        (update' :iter-xf resolve')
        (update' :encoders #(into
                              {}
                              (map
                                (fn [[k v]]
                                  (let [resolved-k
                                        (cond-> k
                                          (symbol? k) resolve)]
                                    [resolved-k v])))
                              (eval %))))))


(defn dependencies [^Class clazz]
  (set (resolve-deps clazz (init-ctx nil))))


(defn depends-on? [^Class dependent ^Class dependency]
  (boolean (get (dependencies dependent) dependency)))


(defmacro defproto [class & opts]
  (let [ctx          (init-ctx opts)
        ^Class clazz (resolve-class class)
        deps         (reverse (resolve-deps clazz ctx))]
    `(u/with-ns "pronto.protos"
       ~@(doall
           (for [dep deps]
             (e/emit-proto-map dep ctx)))

       ~(e/emit-proto-map clazz ctx))))


(defn macroexpand-class [^Class clazz]
  (macroexpand-all `(defproto ~(symbol (.getName clazz)))))



(potemkin/import-vars [pronto.runtime
                       p->
                       pcond->
                       clear-field
                       clear-field!
                       assoc-if])

