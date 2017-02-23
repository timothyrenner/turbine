#  Turbine

Turbine is a dataflow processing library built on Clojure's [core.async](https://clojure.github.io/core.async/).
Turbine is designed for workloads that require complex flows (meaning a branched DAG) and possibly multiple processors.
As such it's not a distributed framework: it's a library built to run on a single machine and take advantage of all available cores.

This is _not_ a production system, and isn't designed to compete with any existing frameworks.
The closest project to it that I've seen is Matz's [streem](https://github.com/matz/streem), which is a DSL for asynchronous stream processing.
Read the "Motivation" section for more details on what turbine does differently and why I think it's useful.
I do need to mention a couple of things first:

1. ***This is a hobby project***, and as such I do have a roadmap of things I'd like to add, but I don't really have anything that resembles a timetable. This is being built in my "spare time" which amounts to a few hours a week because toddlers.

2. ***This project is incomplete***.
There is critical functionality missing that I need to implement before release.
However, what's here in the repository is functional.

## Quick Start

It's probably a good idea to read the whole README, but this will get you started messing around if you're a hands-on learner.

In the project root, fire up a REPL and issue the following `require`:

```clojure
user=> (require '[turbine.demos :refer :all])
nil
```

This loads five functions into the namespace that build sample topologies for interactive use, each of which demonstrates properties of a route in the library:
`scatter-demo`, `splatter-demo`, `select-demo`, `union-demo`, and `gather-demo`.

All of the functions take zero arguments and returns a function that feeds the topology.

```clojure
user=> (def scatter-in (scatter-demo))
#'user/scatter-in
```

So `scatter-in` is a function that can be called like so.

```clojure
user=> (scatter-in "Hi there")
true
Hi there!
Hi there!!
```

All of the functions returned by the demos take a single string except the function returned by `splatter-demo`, which takes a vector of strings.

The topology specifiers can be seen by `source`-ing the demo functions.

## Motivation

Suppose you have a CSV file that's too big for memory - like 20G or something like that - and you need to perform some transformations on it that will break it into a couple of files.
What's the best tool for this work? 
Well, you can't load it into memory, which means that whatever intermediate stage you have between the initial file and the final files needs to be carefully considered.
The first think I personally reach for when faced with a data processing task is the command line -  Unix / Linux command line programs are super fast because they're written in C, and they're built to be chained together with the pipe, which sends data through one line at a time, so memory isn't usually an issue (`sort` is a counter example).
The issue with that is you can't _branch_ a pipe.
You'll be stuck scanning that big file twice to get what you want.
Moreover, all of those tools are single core.
Even if your task _could_ be processed asynchronously, the command line won't be much help.

R's [data.table](https://github.com/Rdatatable/data.table/wiki) or Python's [dask](http://dask.pydata.org/en/latest/) might work because they both operate on disk instead of memory, but they're designed for tabular transformations.
Anything requiring a more specialized treatmemnt (maybe the file's JSON instead?) and you're stuck writing customized code to read and process the data yourself.
This is where turbine is designed to work.

Turbine processes data one record at a time, but does so _asynchronously_, and is thus able to leverage all available processing cores to the extent that the processing task allows.
Transformations are expressed as operations on collections - `map`, `filter`, etc., so there's no boilerplate looping required.
It also grants full flexibility in building the processing flow - branches, conditional branches, and branch convergences are all available. 
It can take data from and write data to multiple sources.
That's because at its core turbine is a stream processor.

## Stream Processing Semantics

Stream processing frameworks / libraries express stream operations in one of two ways, which I call **function-oriented** and **sequence-oriented**.

Function-oriented semantics build a directed graph, with each node being a function and each edge being a stream connecting the nodes.
Data flows though the edges into each node, which process the data and pass it down the graph.
Examples of this approach are [Storm](http://storm.apache.org/), [Kafka Streams](http://www.confluent.io/blog/introducing-kafka-streams-stream-processing-made-simple) (the Processor API), and [Onyx](http://www.onyxplatform.org/).

Sequence-oriented semantics treat the stream as an infinite lazy collection, and use operations like `map` and `filter` to express transformations.
Under the hood, these operations are composed when possible and converted to a directed graph, usually done with a "builder" or "context"-like object.
Examples of this approach are [Spark Streaming](http://spark.apache.org/streaming/), [Flink](http://flink.apache.org/), and Kafka Streams (the high-level DSL).

Each approach has it's tradeoffs: function-oriented semantics allow fine-tuned flow control and provide a very natural way of expressing the computation, but doesn't compose operations for you when available and advanced flow controls introduce complexity (in the [Rich Hickey](http://www.infoq.com/presentations/Simple-Made-Easy) sense).
Collection-oriented semantics are much more concise and automajestically compose operations, but substantially limit flow control.

Turbine combines the expressive power of collection-oriented operations with the advanced flow control and natural representation of function-oriented semantics.

## Core Concepts

There are two core concepts to turbine: **channels** and **routes**.
Channels transport **messages** through the routes, transforming them along the way with [transducers](http://clojure.org/reference/transducers).
Routes transfer messages between channels according to the route type without altering the messages.
This is important; in many function-oriented systems ([Onyx is an exception](http://www.onyxplatform.org/docs/user-guide/latest/flow-conditions.html)), the nodes of the graph are charged with transforming _and_ routing the data, requiring the transformation nodes to be substantially more aware of the overall graph structure than they need to be.
By separating the message transformations from the flow control and making routes first-class citizens, the resulting directed graph has _routes_ as nodes, and _channels_ as edges, with the data transformations pushed into the edges.
I call this **route-oriented** semantics.

### Channels

Channels are literally core.async channels.
To specify a channel in the directed graph (henceforth referred to as a _topology_), use a vector of the following form:

```clojure
[chan-alias transducer optional]
```

`chan-alias` is a topology-local alias for the channel.
For almost all of the routes, the outbound channels are fully defined.
The alias is used to select the inbound channel for routes further "down" the topology.

`transducer` is the transducer to attach to the channel.
This is where the collection-oriented semantics enter; a transducer is the _essence_ of a collection operation without the actual collection.
This makes them exceptionally easy to test since there are no framework components to mock and no restrictions regarding the shape or type of the messages.
Just use `eduction` on some example messages and you have all of your business logic directly at your disposal for testing, with no need for additional abstractions.

Since transducers are composable, each channel's transformations can be represented as a single transducer, greatly simplifying topology specification as well as testing.
I plan on making the transducer optional, since the values don't always need to be transformed and `(map identity)` gets old pretty quickly.
It's not implemented yet, though.

`optional` is used for certain routes that select the output channel based on some criteria.
Currently the only route that uses this is `select`.
I'll defer discussing this value until the routes have been introduced.

Here are a couple of examples:

```clojure
;; Appends an exclamation point to the value.
[:exc1 (map #(str % "!"))]

;; Filters two-vector values if the second is less than the first 
;; and selects the second value.
[:alert-val (comp (filter (fn [[x y]] (< y x)))
                  (map (fn [[x y]] y)))]

;; Flattens vector messages into a stream of single elements.
[:flattener cat]
```

Before moving on I'd like to point out a critical detail when dealing with stateful transducers.
When defining a stateful transducer, it's best practice to create a function that returns the transducer with `defn` rather than `def`-ing it directly (just like most of the transducers in clojure.core).
Doing so ensures that if the transducer-generator is used in multiple parts of the topology the state remains independent.
The reason is that transducer states are typically (though not necessarily) `volatile`, which is not thread-safe like an `atom`.

### Routes

Routes are the switchpoints that transfer messages between channels asynchronously.
There are three fundamental routes (with no embedded logic), and a couple more I consider to be "composite" routes.
A composite route can be emulated with a fundamental route and certain transducers on the output channels.
For reasons related to both performance and conceptual separation of concerns it can sometimes make more sense to embed the logic in the route.

This library has three fundamental routes and three composite routes already implemented.

There are also two special routes for inputs and sinks.
They're pretty easy.

***Currently these are the only routes available, but I do plan on implementing a means of adding custom routes.***

#### Scatter

The scatter route is a fundamental route that takes a single inbound message and clones it into one or more outbound channels.
It's specified as follows:

```clojure
[:scatter in-chan-alias [out-chan-specifier1 out-chan-specifier2 ...]]
```

Here `in-chan-alias` is the topology-local alias of the inbound channel, and `out-chan-specifier` 1 and 2 are full channel specifiers with transducers (defined in the previous section).
Here's a concrete example:

```clojure
[:scatter :in-chan1 [[:exc1 (map #(str % "!"))]
                     [:exc2 (map #(str % "!!"))]]]
```

This specifier scatters any values on `:in-chan` onto two outbound channels, one of which appends a single exclamation mark and the other of which appends two.
Exciting.

And doubly exciting.

#### Union

The union route is  another implemented fundamental route.
It takes messages from multiple channels and merges them onto a single outbound channel.
It's specified as follows:

```clojure
[:union [in-alias1 in-alias2 ...] out-chan-specifier]
```

Here `in-alias` 1 and 2 are input channel aliases, and `out-chan-specifier` is a single full channel specifier.
Here's an example:

```clojure
[:union [:exc1 :exc2] [:really-exc (map #(str % "!!"))]]
```

The union reads from `:exc1` and `:exc2`, "funneling" their messages into a single channel which appends two _more_ exclamation points.
So if `:exc` 1 and 2 come from the scatter example above, you'll see messages coming out the other end with three and four exclamation points.

#### Gather

The gather route reads from multiple input channels and _concatenates_ their messages into a vector that gets passed to a single output channel.
This route will block until all of the input channels produce values, which makes it a dangerous route to put into a topology; it can plug the whole thing up.
For batch-type jobs this isn't a concern, but if there's a possibility of data loss for a streaming job it can cause trouble unless all of the input channels are source from the same input (imagine using `scatter` on three separate channels and unifying them later).
Here's what it will look like syntactically:

```clojure
[:gather [in-alias1 in-alias2 ...] out-chan-specifier]
```

Here `in-alias` 1 and 2 are the aliases for input channels, and `out-chan-specifier` is the specifier for the output channel.

Here's an example of what this looks like:

```clojure
[:gather [:exc1 :exc2] [:cat-strings (map (fn [[l r]] (str l r)))]]
```

If `exc` 1 and 2 are the outputs of the scatter route above, this route will take messages with one and two exclamation points and concatenate the two strings into a single string.
This exact scenario is an instance when the gather route would be safe; if you're scattering directly onto two channels then you're guaranteed to get them both into the gather route in the same order, though not necessarily at the same time (if one of the gather's input channels has a more computationally intense transducer than the other).
So there will be some blocking, but not _infinite_ blocking.

#### Select

The select route is a composite route that receives messages from a single inbound channel and uses a _selector_ function to determine which outbound channels receive it.
It's somewhat similar to a multimethod dispatch, except there can be multiple outbound channels selected (and there's no hierarchy or any of that ... maybe it's not that similar).
There really isn't a good theoretical reason I can think of to prohibit the number of matching outbound channels.
Here's what a select route specifier looks like

```clojure
[:select in-chan-alias 
         [out-chan-specifier-and-selector1 
          out-chan-specifier-and-selector2 ...] 
        selector-fn]
```

The selector values are attached to the outbound channel specifiers, so those have the form

```clojure
[chan-alias xform selector-value]
```

The selector value is matched against the value returned by applying the dispatch function to the message.
Here's an example:

```clojure
[:select :ints [[:evens (map inc) 0]
                [:odds  (map inc) 1]] 
         (fn [x] (mod x 2))]
```

This takes an inbound integer message and routes it to the `:evens` channel if the number is even, and the `:odds` if the number is odd.

Note that this can be implemented with a scatter route by attaching `filter` transducers to each of the outbound channels, which makes it a composite route according to the vocabulary I made up.

**NOTE** In a future version the `selector-value` argument of the outbound channel specifier will be optional, and the `selector-fn` values will be hashed to select the outbound channel.

#### Splatter

The splatter route is a composite route that receives vector messages and "splats" each element of the vector onto an output channel.

```clojure
[:splatter in-chan-alias [out-chan-specifier1 out-chan-specifier2 ... ]]
```

Where the output channel specifiers are the standard ones seen in the fundamental routes.
Here's an example specification:

```clojure
[:splatter :vector-in [[:exc1 (map #(str % "!"))]
                       [:exc2 (map #(str % "!!"))]]]
```

This takes a vector of strings coming from `vector-in`, putting the first element onto `exc1` and the second onto `exc2`.

```clojure
["Hi" "there"]    ;; Input to the above splatter route. 
"Hi!" 
"there!!" ;; Output to the above splatter route.
```

This route is a composite route because it can be implemented by using `scatter` and `map` transducers that select each element of the vector.
If there are more elements in the input vector than there are output channels then the vector is truncated.
If there are more output channels than elements in the input vector then only channels corresponding to those elements are written to.

#### Spread

The spread route is a special case of select that takes the value on the inbound channel and places it onto one of the outbound channels.
It selects the outbound channel based on a round robin scheduler.
This route can be used to distribute computational workload amongst independent threads of execution.

```clojure
[:spread in-chan-alias [out-chan-specifier1 out-chan-specifier2 ...]]
```

The outbound channel specifiers are normal specifiers.

```clojure
[:spread :in1 [[:out-chan-1 (map some-slow-function)]
               [:out-chan-2 (map some-slow-function)]]]
```

This takes values from `:in1` and alternates between `:out-chan-1` and `:out-chan-2`, which is good because I get the impression that the outbound transducers have a pretty slow function attached to them.

#### In

The in specifier defines an input, creating an alias and defining any transducers on the channel.

```clojure
[:in in-chan-alias in-xform]
```

As an example, the following input increments any values passed into the topology for this channel.

```clojure
[:in :integers-in (map inc)]
```

I'll discuss this a little later, but the number of `in` routes determines the number of input functions returned when the topology is built.

#### Sink

The sink specifier defines an "output" function whose return value isn't used (meaning it's used purely for side effects).
I'll discuss the importance of sinks in the next section.

```clojure
[:sink in-chan-alias sink-fn]
```

Here's an example of a very simple sink.

```clojure
[:sink :exc1 println]
```

This takes all values put onto the `exc1` channel and "sinks" them to stdout.

The topology-wide rule regarding sinks is that any channels in the topology that _aren't_ being ingested into a route _must_ go into a sink.
If this isn't done, the channel's buffers will fill up and the topology will freeze when the internal `core.async` puts can't complete.

On a final note, it's worth mentioning that the scatter and union routes already have primitives in `core.async` as `tap/mult` and `alt/alts`, this library just wraps them into a data structure.

### Topologies
 
 There are differences between turbine's treatment of the topology graphs and other stream processing frameworks.
 They all stem from this:
 
 > A topology is a collection of functions that send data through the DAG and return no value.
 
Most stream processing frameworks (plus Kafka streams) treat the topology as this _other_ entity that gets built through some context or builder, and then submitted to a cluster manager, or started by the context.
This hard-wires the sources into the topology graphs, making them closed systems.
Moreover, they're _single-purpose_ systems because that context/builder is also mutable (Onyx and Storm's Clojure DSL are the exception here, they define the topology as a data structure - turbine does as well. I sense a common thread ....).
Mutable operations don't compose as well as immutable ones.

Turbine doesn't bother wiring in the sources at all.
For stream sources we can use `stdin`, a socket, or a Kafka topic; for batch sources we can use a file.
Ther input sources shouldn't be complected with the processing itself (naturally this is the rationale behind the existence of transducers - reducing functions don't actually need to know they're attached to a collection).
Instead, turbine _defines_ the topology as the functions that feed it.
These functions each take a single value and return nothing, **which makes them the same kind of function `sink` expects**.
This makes turbine topologies composable, just like transducers.
Define the topology in pieces and use the entry point functions as sink operators when you want to stick them together.
Another advantage is that turbine doesn't require special "connectors" to plug a source into the topology - just use the entry points like any other function.

To make a topology, use the `make-topology` function

```clojure
(def entry-points (make-topology topology-specifier))
```

`entry-points` is a vector of functions that put values onto the channels specified by the `in` specifiers (in the same order they were specified).
Assuming `entry-points` has only one element, the topology is executed as follows:

```clojure
((first entry-points) new-message)
```

This leaves it to the user to wire the topology to the source. 
For example, this function could be used as a callback, be placed in a go-loop, or dropped into another topology.

I do have plans for making `make-topology` return the function directly when there's only one entry point into the topology for convenience.

## Examples

Here are some topology examples for each of the routes.
Each of these is implemented in the `turbine.demos` namespace.

The examples assume the following namespace declaration:

```clojure
(ns my-ns
    (:require [turbine.routes :refer [make-topology]]))
```

### Scatter

This topology takes a single value, scatters it onto two channels, one appending a single exclamation point, the other appending two.

```clojure
(make-topology
  [[:in :in1 (map identity)]
   ;; Scatter clones the messages onto the output channels.
   [:scatter :in1 [[:exc1 (map #(str % "!"))]
                   [:exc2 (map #(str % "!!"))]]]
   [:sink :exc1 println]
   [:sink :exc2 println]]))
```

You're going to get really tired of looking at `(map identity)`.
I do plan on making the transducer optional in the near future to avoid this.

### Splatter

This topology takes a vector input and uses splatter to append a single exclamation point to the first element and two exclamation points to the second.

```clojure
(make-topology
  [[:in :in1 (map identity)]
   ;; Splatter takes a vector and "splats" it onto the output channels.
   [:splatter :in1 [[:exc1 (map #(str % "!"))]
                    [:exc2 (map #(str % "!!"))]]]
   [:sink :exc1 println]
   [:sink :exc2 println]])
```

### Select

This topology takes a single value and uses select to append a single exclamation point to lower case words and two exclamation points to upper case words.
As previously mentioned, this can be done with scatter and filter.

```clojure
(make-topology
  [[:in :in1 (map identity)]
   ;; Outbound aliases in a select have an additional element, which is
   ;; matched against the value of the selector function applied to the 
   ;; inbound message.
   [:select :in1 [[:exc1 (map #(str % "!")) true]
                  [:exc2 (map #(str % "!!")) false]]
            ;; This selector function is boolean, but it doesn't have to be.
            (fn [x] (Character/isLowerCase (first x)))]
   [:sink :exc1 println]
   [:sink :exc2 println]])
```

### Spread

This topology takes a single value and alternates between appending one and two exclamation points.

```clojure
(make-topology
  [[:in :in1 (map identity)]
   [:spread :in1 [[:exc1 (map #(str % "!"))]
                  [:exc2 (map #(str % "!!"))]]]
   [:sink :exc1 println]
   [:sink :exc2 println]])
```

### Union

This topology takes a single value and copies it, one with a single exclamation point and another with two.
These are "flattened" onto the stream by the union route, so one input will result in two sinks.

This topology can also be built with a composition of `map` and `cat` using no routes whatsoever.

```clojure
(make-topology
  [[:in :in1 (map identity)]
   [:scatter :in1 [[:exc1 (map #(str % "!"))]
                   [:exc2 (map #(str % "!!"))]]]
   [:union [:exc1 :exc2] [:ident (map identity)]]
   [:sink :ident println]])
```

## Contents

The package currently contains three namespaces: `turbine.core`, `turbine.routes`, `turbine.demos`.
`turbine.core` contains the topology building machinery.
`turbine.routes` is the meat of the library, containing all of the logic for establishing the routes.
`turbine.demos` contains all of the topologies in the "Examples" section with functions to return the entry points.
It's designed for interactive use in the REPL.

```clojure
user=> (require '[turbine.demos :refer 
        [scatter-demo splatter-demo select-demo union-demo]])
nil

user=> (def scatter-in (scatter-demo))
#'user/scatter-in

user=> (scatter-in "hello")
true
hello!
hello!!
```

## Future Work

There are a number of things to do before an "official" release.

1. Create a `defroute` macro or function (if I can get away with it) for custom routing.
2. Make transducers optional in the topology specification.
3. Enable batch computation.
4. Transducers and routes for aggregations and joins.
5. A command line interface.