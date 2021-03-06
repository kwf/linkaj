(ns map-experiments.directed-graph.core
  (:require [clojure.set                             :refer :all]
            [map-experiments.common                  :refer :all]
            [map-experiments.smart-maps.protocol     :refer :all]
            [map-experiments.smart-maps.bijection    :refer :all]
            [map-experiments.smart-maps.surjection   :refer :all]
            [map-experiments.smart-maps.bipartite    :refer :all]
            [map-experiments.smart-maps.attr-map     :refer :all]
            [map-experiments.directed-graph.protocol :refer :all]
            [map-experiments.directed-graph.macro    :refer :all])
  (:import [clojure.lang
            IPersistentMap IPersistentSet IPersistentCollection ILookup IFn IObj IMeta Associative MapEquivalence Seqable SeqIterator]))

(declare
  edges-touching
  starting-node-seq starting-edge-seq
  remove-edges remove-nodes
  graph-node graph-edge
  nodes edges
  node? edge?
  relation
  identity-constraint)

; Parse-relations takes a list of attributes and a relations-map and returns a 2-tuple of maps: the relations contained in the map, and all other attributes in the map. It's private as there's little to no use for it outside the type definition.

(defn- parse-relations
  ([attributes relations-set]
   (let [relations  (select-keys attributes relations-set)
         rest-attrs (apply dissoc attributes (keys relations))]
        [relations rest-attrs])))

; The type definition itself!

(deftype DirectedGraph [nodes-set
                        nodes-map
                        edges-relations
                        edges-map
                        node-id-seq
                        edge-id-seq
                        relations-set
                        relations-map
                        constraints-fn
                        metadata]
  
  IDirectedGraph
  
  ; Methods acting on nodes:
  (nodes* [this]
          (map (partial graph-node this)
               (when (< 0 (count nodes-set)) nodes-set)))
  (nodes* [this query]
          (if (not (seq query))
              (nodes* this)
              (let [nodes-lists
                    (for [[a vs] query]
                         (apply union
                           (if (relation-in? this a)
                               (for [v (sequentialize vs)]
                                    (cond (nil? v) nil
                                          (node? v)
                                          (map #(attr-get edges-map %
                                                          (opposite relations-map a))
                                               (keys-with edges-map a (id v)))
                                          (edge? v)
                                          [(attr-get edges-map (id v)
                                                     (opposite relations-map a))]
                                          :else
                                          (throw (IllegalArgumentException.
                                                 "Nodes can only be related to nodes, and by extension, to edges."))))
                               (for [v (sequentialize vs)]
                                    (keys-with nodes-map a v)))))]
                   (map (partial graph-node this)
                        (if (< 1 (count nodes-lists))
                            (seq (apply intersection (map setify nodes-lists)))
                            (first nodes-lists))))))
  (node-in? [this o]
            (and (node? o)
                 (contains? nodes-set (id o))))
  (add-node* [this attributes]
             (cond (some relations-set (keys attributes))
                   (throw (IllegalArgumentException.
                            "Attributes may not be identical to existing relations"))
                   (not (seq node-id-seq))
                   (throw (IllegalStateException.
                            "Empty internal node id sequence; check custom specifications for this parameter to ensure that sequence specified is infinite"))
                   (contains? nodes-map (first node-id-seq))
                   (throw (IllegalStateException.
                            "Encountered duplicate in internal node id sequence; check custom specifications for this parameter to ensure that sequence specified is non-repeating"))
                   :else
                   (let [node-key (first node-id-seq)
                         new-nodes-map (assoc nodes-map node-key attributes)]
                        (#(constraints-fn
                            :node :add
                            (graph-node this node-key)
                            (graph-node % node-key)
                            this
                            %)
                           (DirectedGraph.
                             (conj nodes-set node-key)
                             new-nodes-map
                             edges-relations edges-map
                             (rest node-id-seq)
                             edge-id-seq relations-set relations-map constraints-fn metadata)))))
  (remove-node [this n]
               (if (not (node-in? this n))
                   (throw (IllegalArgumentException.
                            "Cannot remove node whose origin is another graph."))
                   (let [node-key (id n)
                         edges-to-remove (edges-touching this n)
                         new-nodes-map (dissoc nodes-map node-key)]
                        (if (seq edges-to-remove)
                            (let [new-graph (remove-edges this edges-to-remove)]
                                 (remove-node new-graph
                                              (graph-node new-graph (id n))))
                            (#(constraints-fn
                                :node :remove
                                (graph-node this node-key)
                                (graph-node % node-key)
                                this
                                %)
                               (DirectedGraph.
                                 (disj nodes-set node-key)
                                 new-nodes-map
                                 edges-relations edges-map
                                 (if (node-in? this n)
                                     (cons node-key node-id-seq)
                                     node-id-seq)
                                 edge-id-seq relations-set relations-map constraints-fn metadata))))))
  (assoc-node* [this n attributes]
               (if (not (node-in? this n))
                   (throw (IllegalArgumentException.
                            "Cannot assoc to node whose origin is in another graph."))
                   (let [node-key (id n)
                         new-nodes-map (assoc nodes-map node-key attributes)]
                        (cond (some relations-set (keys attributes))
                              (throw (IllegalArgumentException.
                                       "Attributes may not be existing relations"))
                              (not (node-in? this n))
                              (throw (IllegalArgumentException.
                                       "Node must exist before assoc-ing onto it; use add-node to create a new node with attributes"))
                              :else
                              (#(constraints-fn
                                  :node :assoc
                                  (graph-node this node-key)
                                  (graph-node % node-key)
                                  this
                                  %)
                                 (DirectedGraph.
                                   nodes-set
                                   new-nodes-map
                                   edges-relations edges-map node-id-seq edge-id-seq relations-set relations-map constraints-fn metadata))))))
  (dissoc-node* [this n attribute-keys]
                (if (not (node-in? this n))
                    (throw (IllegalArgumentException.
                             "Cannot dissoc from node with origin in another graph."))
                    (let [node-key (id n)
                          new-nodes-map (reduce #(attr-dissoc %1 node-key %2)
                                                nodes-map attribute-keys)]
                         (#(constraints-fn
                             :node :dissoc
                             (graph-node this node-key)
                             (graph-node % node-key)
                             this
                             %)
                            (DirectedGraph.
                              nodes-set
                              new-nodes-map
                              edges-relations edges-map node-id-seq edge-id-seq relations-set relations-map constraints-fn metadata)))))
  
  ; Methods acting on edges:
  (edges* [this]
          (map (partial graph-edge this)
               (when (< 0 (count edges-map))
                     (keys edges-map))))
  (edges* [this query]
          (if (not (seq query))
              (edges* this)
              (let [edges-lists
                    (for [[a vs] query]
                         (apply union
                           (if (relation-in? this a)
                               (for [v (sequentialize vs)]
                                     (cond (nil? v) nil
                                           (node? v)
                                           (keys-with edges-map a (id v))
                                           (edge? v)
                                           (keys-with
                                             edges-map a
                                             (attr-get edges-map (id v)
                                                       (opposite relations-map a)))
                                           :else
                                           (throw (IllegalArgumentException.
                                                        "Edges can only be related to nodes, and by extension, to edges."))))
                                    (for [v (sequentialize vs)]
                                         (keys-with edges-map a v)))))]
                   (map (partial graph-edge this)
                        (if (< 1 (count edges-lists))
                            (seq (apply intersection (map setify edges-lists)))
                            (first edges-lists))))))
  (edge-in? [this o]
            (and (edge? o)
                 (contains? edges-map (id o))))
  (add-edge* [this attributes]
             ; Validating that edge has exactly two relations, and they point to existing nodes in the graph
             (let [[relations rest-attrs] (parse-relations attributes relations-set)
                   edge-key (first edge-id-seq)
                   new-edges-map (assoc edges-map edge-key
                                        (merge rest-attrs
                                               (zipmap (keys relations)
                                                       (map id (vals relations)))))]
                  (cond (not (seq edge-id-seq))
                        (throw (IllegalStateException.
                                 "Empty internal edge id sequence; check custom specifications for this parameter to ensure that sequence specified is infinite"))
                        (contains? edges-map (first edge-id-seq))
                        (throw (IllegalStateException.
                                 "Encountered duplicate in internal edge id sequence; check custom specifications for this parameter to ensure that sequence specified is non-repeating"))
                        (or (< (count relations) 2) (> (count relations) 2))
                        (throw (IllegalArgumentException.
                                 "An edge cannot be created without a relation to exactly 2 existing nodes"))
                        (= (count relations) 2)
                        (let [[r1 r2] (keys relations)]
                             (cond (not (= r1 (opposite relations-map r2)))
                                   (throw (IllegalArgumentException.
                                            "Relations for edges must be opposites"))
                                   (not (and (node-in? this (relations r1))
                                             (node-in? this (relations r2))))
                                   (throw (IllegalArgumentException.
                                            "Edges must connect existing nodes"))
                                   :else
                                   (#(constraints-fn
                                       :edge :add
                                       (graph-edge this edge-key)
                                       (graph-edge % edge-key)
                                       this
                                       %)
                                      (DirectedGraph.
                                        nodes-set nodes-map
                                        (assoc edges-relations edge-key #{r1 r2})
                                        new-edges-map
                                        node-id-seq
                                        (rest edge-id-seq)
                                        relations-set relations-map constraints-fn metadata)))))))
  (remove-edge [this e]
               (if (not (edge-in? this e))
                   (throw (IllegalArgumentException.
                            "Cannot remove edge with origin in another graph."))
                   (let [edge-key (id e)
                         new-edges-map (dissoc edges-map edge-key)]
                        (#(constraints-fn
                            :edge :remove
                            (graph-edge this edge-key)
                            (graph-edge % edge-key)
                            this
                            %)
                           (DirectedGraph.
                             nodes-set nodes-map
                             (dissoc edges-relations edge-key)
                             new-edges-map
                             node-id-seq
                             (if (edge-in? this edge-key)
                                 (cons edge-key edge-id-seq)
                                 edge-id-seq)
                             relations-set relations-map constraints-fn metadata)))))
  (assoc-edge* [this e attributes]
               ; Massive validation step to check that the new attributes don't violate the conditions of being a properly formed edge...
               (let [edge-key (id e)
                     new-edges-map (assoc edges-map edge-key attributes)]
                    (cond (not (edge-in? this e))
                          (throw (IllegalArgumentException.
                                   "Cannot assoc on edge with origin in another graph."))
                          (let [[relations rest-attrs]
                                (parse-relations attributes relations-set)
                                [r1 r2] (keys relations)]
                               (case (count relations)
                                     0 true
                                     1 (cond (not (attr-get edges-map edge-key
                                                            (opposite relations-map r1)))
                                             (throw (IllegalArgumentException.
                                                      "The type of relation for an edge may not be altered."))
                                             (not (node-in? this (relations r1)))
                                             (throw (IllegalArgumentException.
                                                      "Edges must be connected to existing nodes"))
                                             :else true)
                                     2 (cond (or (not (= r1 (opposite relations-map r2)))
                                                 (not (attr-get edges-map edge-key
                                                                (opposite relations-map r1))))
                                             (throw (IllegalArgumentException.
                                                      "The type of relation for an edge may not be altered."))
                                             (not (and (node-in? this (relations r1))
                                                       (node-in? this (relations r2))))
                                             (throw (IllegalArgumentException.
                                                      "Edges must be connected to existing nodes"))
                                             :else true)
                                     (throw (IllegalArgumentException.
                                              "Edges must relate to exactly 2 nodes"))))
                          (#(constraints-fn
                              :edge :assoc
                              (graph-edge this edge-key)
                              (graph-edge % edge-key)
                              this
                              %)
                             (DirectedGraph.
                               nodes-set nodes-map edges-relations
                               new-edges-map
                               node-id-seq edge-id-seq relations-set relations-map constraints-fn metadata)))))
  (dissoc-edge* [this e attribute-keys]
                ; Validate that there are no relations being dissoced
                (if (not (edge-in? this e))
                    (throw (IllegalArgumentException.
                             "Cannot dissoc from edge with origin in another graph."))
                    (let [edge-key (id e)
                          new-edges-map (reduce #(attr-dissoc %1 edge-key %2)
                                                edges-map attribute-keys)
                          [relations rest-attrs]
                          (parse-relations
                            (into {} (map vector attribute-keys (repeat nil)))
                            relations-set)]
                         (if (not= 0 (count relations))
                             (throw (IllegalArgumentException.
                                      "An edge cannot be disconnected from a node without being connected to another node"))
                             (#(constraints-fn
                                 :edge :dissoc
                                 (graph-edge this edge-key)
                                 (graph-edge % edge-key)
                                 this
                                 %)
                                (DirectedGraph.
                                  nodes-set nodes-map edges-relations
                                  new-edges-map
                                  node-id-seq edge-id-seq relations-set relations-map constraints-fn metadata))))))
  
  Relational
  (relations [this] (set (map set relations-map)))
  (related-in? [this r1 r2]
               (and (relation-in? this r1)
                    (relation-in? this r2)
                    (= r1 (opposite relations-map r2))))
  (relation-in? [this r]
                (or (contains? relations-map r)
                    (contains? (inverse relations-map) r)))
  (add-relation [this r1 r2]
                (DirectedGraph.
                  nodes-set nodes-map edges-relations edges-map node-id-seq edge-id-seq
                  (conj relations-set r1 r2)
                  (assoc relations-map r1 r2)
                  constraints-fn metadata))
  (remove-relation [this r1 r2]
                   (if (and (related-in? this r1 r2)
                            (nil? (keys-with-attr edges-map r1))
                            (nil? (keys-with-attr edges-map r2)))
                       (DirectedGraph.
                         nodes-set nodes-map edges-relations edges-map node-id-seq edge-id-seq
                         (disj relations-set r1 r2) (dissoc (rdissoc relations-map r1) r1)
                         constraints-fn metadata)
                       (throw (IllegalArgumentException.
                                "Relation could not be removed from graph for one of the following reasons: a) the two relations given are not each others' opposites; b) there are existing edges along this relation"))))
  
  Constrained
  (add-constraint [this new-constraint]
                  (DirectedGraph.
                    nodes-set nodes-map edges-relations edges-map node-id-seq edge-id-seq relations-set relations-map
                    (fn [element action old-piece new-piece old-graph new-graph]
                      (new-constraint
                        element action old-piece new-piece old-graph
                        (constraints-fn
                          element action old-piece new-piece old-graph new-graph)))
                    metadata))
  (reset-constraints [this]
                     (DirectedGraph.
                       nodes-set nodes-map edges-relations edges-map node-id-seq edge-id-seq relations-set relations-map
                       identity-constraint
                       metadata))
  (constraints [this] constraints-fn)
  
  IPersistentCollection
  (equiv [this o] 
         (and (isa? (class o) DirectedGraph)
              (= nodes-set       (.nodes-set       ^DirectedGraph o))
              (= nodes-map       (.nodes-map       ^DirectedGraph o))
              (= edges-map       (.edges-map       ^DirectedGraph o))
              (= edges-relations (.edges-relations ^DirectedGraph o))
              (= relations-map   (.relations-map   ^DirectedGraph o))))
  (empty [this]
         (DirectedGraph.
           (empty nodes-set)
           (empty nodes-map)
           (empty edges-relations)
           (empty edges-map)
           (starting-node-seq)
           (starting-edge-seq)
           relations-set
           relations-map
           constraints-fn
           metadata))
  
  Seqable
  (seq [this]
       (seq {:relations relations-map
             :nodes (-#> this nodes)
             :edges (-#> this edges)}))
  
  Object
  (toString [this]
            (str (into {} (seq this))))
  
  IMeta
  (meta [this] metadata)
  
  IObj
  (withMeta [this new-meta]
            (DirectedGraph.
              nodes-set nodes-map edges-relations edges-map node-id-seq edge-id-seq relations-set relations-map constraints-fn
              new-meta)))

; Defining GraphNodes and GraphEdges, which are emitted from the graph in response to queries:

; GraphNodes are ephemeral maps which contain a hidden id. They are emitted from node queries and their keys/values are looked up lazily, which means that one can efficiently map over a set of GraphNodes without the program having to look up every value in each node.

; Forces all the attributes of a node into a hash-map.
(defn make-node-map [graph id]
  (get (.nodes-map ^DirectedGraph graph) id))

(deftype GraphNode [metadata graph id]
  IComponent
  (id [this] id)
  IPersistentMap
  (assoc [this k v]
         (with-meta
           (assoc (make-node-map graph id) k v)
           metadata))
  (without [this k]
           (with-meta
             (dissoc (make-node-map graph id) k)
             metadata))
  (iterator [this]
    (SeqIterator. (seq this)))
  IPersistentCollection
  (cons [this x]
        (with-meta
          (conj (make-node-map graph id) x)
          metadata))
  (equiv [this o]
         (and (isa? (class o) GraphNode)
              (= id (.id ^GraphNode o))
              (= graph (.graph ^GraphNode o))))
  (empty [this] (with-meta {} metadata))
  IObj (withMeta [this new-meta]
                 (GraphNode. new-meta graph id))
  Associative
  (containsKey [this k]
               (if (attr-get (.nodes-map ^DirectedGraph graph) id k) true false))
  (entryAt [this k]
           (when (contains? this k)
                 (clojure.lang.MapEntry. k (get this k))))
  ILookup
  (valAt [this k] (attr-get (.nodes-map ^DirectedGraph graph) id k))
  (valAt [this k not-found] (attr-get (.nodes-map ^DirectedGraph graph) id k not-found))
  Seqable (seq [this] (seq (get (.nodes-map ^DirectedGraph graph) id)))
  IFn (invoke [this k] (get this k))
  IMeta (meta [this] metadata)
  Object (toString [this] (str (get (.nodes-map ^DirectedGraph graph) id)))
  MapEquivalence)

; Forces all the attributes and relations of an edge into a non-lazy map.
(defn- make-edge-map [graph id]
  (let [edge-map (get (.edges-map ^DirectedGraph graph) id)
        rels (keys (select-keys edge-map (apply concat (.relations-map ^DirectedGraph graph))))]
       (reduce conj edge-map
               (map #(vector % (graph-node graph (get edge-map %)))
                    rels))))

; A GraphEdge is like a GraphNode. Note that it "contains" GraphNodes as values for its relation keys.

(deftype GraphEdge [metadata graph id]
  IComponent
  (id [this] id)
  Relational
  (relations [this]
             (hash-set (get (.edges-relations ^DirectedGraph graph) id)))
  (related-in? [this r1 r2]
               (every? (relation this) [r1 r2]))
  (relation-in? [this r]
                (contains? (relation this) r))
  IPersistentMap
  (assoc [this k v]
         (with-meta
           (assoc (make-edge-map graph id) k v)
           metadata))
  (without [this k]
           (with-meta
             (dissoc (make-edge-map graph id) k)
             metadata))
  (iterator [this]
    (SeqIterator. (seq this)))
  IPersistentCollection
  (cons [this x]
        (with-meta
          (conj (make-edge-map graph id) x)
          metadata))
  (equiv [this o]
         (and (isa? (class o) GraphEdge)
              (= id (.id ^GraphEdge o))
              (= graph (.graph ^GraphEdge o))))
  (empty [this] (with-meta {} metadata))
  (count [this] (count (make-edge-map graph id)))
  IObj (withMeta [this new-meta]
                 (GraphEdge. new-meta graph id))
  Associative
  (containsKey [this k]
               (if (attr-get (.edges-map ^DirectedGraph graph) id k) true false))
  (entryAt [this k]
           (when (contains? this k)
                 (clojure.lang.MapEntry. k (get this k))))
  ILookup
  (valAt [this k] (get this k nil))
  (valAt [this k not-found]
         (if (contains? this k)
             (let [v (attr-get (.edges-map ^DirectedGraph graph) id k)]
                  (if (relation-in? graph k)
                      (graph-node graph v)
                      v))
             not-found))
  Seqable
  (seq [this]
       (seq (make-edge-map graph id)))
  Object
  (toString [this]
            (str (make-edge-map graph id)))
  IFn (invoke [this k] (get this k))
  IMeta (meta [this] metadata)
  MapEquivalence)

; Private constructors for graph nodes and edges. You shouldn't use these; graph nodes and edges will be generated automatically from queries and should not be manually constructed by client code. If an id does not exist in the graph, these return nil.
(defn- graph-node [graph id]
  (let [n (GraphNode. nil graph id)]
    (when (node-in? graph n) n)))
(defn- graph-edge [graph id]
  (let [e (GraphEdge. nil graph id)]
    (when (edge-in? graph e) e)))

; Default configuration for internal node and edge seqs:
(defn- starting-node-seq []
  (iterate (comp inc inc) 0))
(defn- starting-edge-seq []
  (iterate (comp inc inc) 1))

; The identity constraint
(defn identity-constraint
  [element action old-piece new-piece old-graph new-graph]
  new-graph)

; Factory function for digraphs:
(defn digraph
  ([& {:keys [relations constraints]}]
   (reduce add-constraint
           (reduce (partial apply add-relation)
                   (DirectedGraph.
                     (hash-set)
                     (attr-map)
                     (hash-map)
                     (attr-map)
                     (starting-node-seq)
                     (starting-edge-seq)
                     (hash-set)
                     (bijection)
                     identity-constraint
                     (hash-map))
                   relations)
           constraints)))

; Variadic map-destructing methods for protocol methods which take maps. This means that the syntax for queries can be *much* more succinct. Note that every method here simply destructures its rest arguments as a map and uses the corresponding asterisk method from the protocol.

(defgraphfn nodes 
  "Returns all graph nodes matching the query."
  ([graph & {:as query}]
   (nodes* graph query)))
(defgraphfn add-node
  "Adds a node with attributes to the graph."
  ([graph & {:as attributes}] (add-node* graph attributes)))
(defgraphfn assoc-node
  "Associates node n with attributes."
  ([graph n & {:as attributes}] (assoc-node* graph n attributes)))
(defgraphfn dissoc-node
  "Dissociates node n from attribute-keys."
  ([graph n & attribute-ks] (dissoc-node* graph n attribute-ks)))

(defgraphfn edges
  "Returns all graph edges matching the query."
  ([graph & {:as query}] (edges* graph query)))
(defgraphfn add-edge
  "Adds an edge with attributes to the graph. Attributes must contain exactly two relations, and they must be each others' opposites."
  ([graph & {:as attributes}] (add-edge* graph attributes)))
(defgraphfn assoc-edge
  "Associates edge-key with attributes. This can change relations."
  ([graph n & {:as attributes}] (assoc-edge* graph n attributes)))
(defgraphfn dissoc-edge
  "Dissociates edge-key from attribute-keys. Relations cannot be dissociated."
  ([graph n & attribute-ks] (dissoc-edge* graph n attribute-ks)))

; Additional methods...

; Predicates for being nodes and edges:

(defn node?
  "Tests if its argument is a GraphNode."
  ([x] (instance? GraphNode x)))

(defn edge?
  "Tests if its argument is a GraphEdge."
  ([x] (instance? GraphEdge x)))

; Singular selectors for nodes and edges:

(def node
  "For selecting a single node when you know the query is unique."
  (specific nodes))

(def edge
  "For selecting a single edge when you know the query is unique."
  (specific edges))

(def relation
  "Gets a singular relation from things with only one relation."
  (specific relations))

(declare-graph-fns node edge relation) ; We can't inline the fact that these are graph functions, cause we're not using defn, so we declare them explicitly as such rather than using the defgraphfn macro.

; Plural operators for nodes:

(defgraphfn add-nodes
  "Adds all possible nodes matching attributes (format like query) to the graph."
  ([graph & {:as attributes}]
   (reduce add-node* graph (map-cross attributes))))

(defgraphfn remove-nodes
  "Removes all nodes in xs from the graph."
  ([graph xs]
   (reduce remove-node graph xs)))

(defgraphfn assoc-nodes
  "Associates all nodes in xs with the attributes."
  ([graph xs & {:as attributes}]
   (reduce #(assoc-node* %1 %2 attributes)) graph xs))

(defgraphfn dissoc-nodes
  "Dissociates all nodes in xs from the attribute-keys."
  ([graph xs & attribute-keys]
   (reduce #(dissoc-node* %1 %2 attribute-keys)) graph xs))

; Plural operators for edges:

(defgraphfn add-edges
  "Adds all possible edges matching attributes (format like query) to the graph."
  ([graph & {:as attributes}]
   (reduce add-edge* graph (map-cross attributes))))

(defgraphfn remove-edges
  "Removes all edges in edge-keys from the graph."
  ([graph es]
   (reduce remove-edge graph es)))

(defgraphfn assoc-edges
  "Associates all edges in edge-keys with the attributes."
  ([graph es & {:as attributes}]
   (reduce #(assoc-edge* %1 %2 attributes)) graph es))

(defgraphfn dissoc-edges
  "Dissociates all edges in edge-keys from the attribute-keys."
  ([graph es & attribute-keys]
   (reduce #(dissoc-edge* %1 %2 attribute-keys)) graph es))

; Other useful operators:

(defgraphfn edges-touching
  "Finds all edges which are connected by any relation to a particular node."
  ([graph n]
   (mapcat #(-#> graph (edges % n))
           (apply concat (relations graph)))))

(defgraphfn assoc-all
  "Associates every item (edge or node) with the attributes."
  ([graph ks & {:as attributes}]
   (reduce #(assoc %1 %2 attributes)) graph ks))

(defgraphfn add-path
  "Adds edges between each adjacent node given, along the relation given."
  ([graph rels xs & {:as attributes}]
   {:pre (= 2 (count rels))}
   (reduce #(add-edge* %1 (merge attributes {(first rels)  (first %2)
                                             (second rels) (second %2)}))
           graph
           (partition 2 1 xs))))

(defgraphfn add-cycle
  "Adds edges between each adjacent node given, along the relation given, and loops back to the first node given."
  ([graph rels xs & {:as attributes}]
   {:pre (= 2 (count rels))}
   (let [xs (if (sequential? xs) xs [xs])]
        (reduce #(add-edge* %1 (merge attributes {(first rels)  (first %2)
                                                  (second rels) (second %2)}))
                graph
                (partition 2 1 (concat xs [(first xs)]))))))

(defgraphfn nodes-away
  "Finds all nodes which are n edges away from the given set of nodes along the relation given."
  ([graph distance rel xs]
   (let [xs (if (sequential? xs) xs [xs])]
        (cond (< distance 0)
              (recur graph (- distance) (opposite (relations graph) rel) xs)
              (= distance 0) xs
              :else
              (recur graph
                     (dec distance)
                     rel
                     (-#> graph (nodes rel xs)))))))
