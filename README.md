# com.yetanalytics/lrs

[![CI](https://github.com/yetanalytics/lrs/actions/workflows/main.yml/badge.svg)](https://github.com/yetanalytics/lrs/actions/workflows/main.yml) [![Clojars Project](https://img.shields.io/clojars/v/com.yetanalytics/lrs.svg)](https://clojars.org/com.yetanalytics/lrs)

Yet Analytics Core LRS Library. Includes protocols and functions used to implement a conformant [xAPI Learning Record Store](https://github.com/adlnet/xAPI-Spec).

## Usage

This project is set up to use the `clj`/`clojure` executable and `deps.edn`, with a handy `Makefile`.

To use the dev profile, which contains all dev/repl/test stuff, use the `:dev` alias: `-A:dev`.

`Makefile` targets of note:

* `clean` - Clean all build/ephemeral files. Note that this includes node_modules and package.json but not package-lock.json.
* `node_modules` - As a convenience, have `cljs.main` figure out the node deps in `src/main/deps.cljs` and upstream, and pull them. You'll want this before using something like CIDER.
* `repl` - Run a Clojure repl with the `:dev` alias.
* `repl-cljs` - Run a Clojurescript node repl.
* `run-dev` - Start an in-memory implementation of the LRS in Clojure.
* `run-dev-cljs` - Start an in-memory implementation of the LRS in ClojureScript.
* `test-lib-clj` - Run the tests in Clojure.
* `test-lib-cljs` - Run the tests in ClojureScript.
* `test-lib` - Run the tests on both Clojure and ClojureScript.
* `test-conformance-clj-sync` - Run ADL LRS Conformance Test Suite on a synchronous Clojure LRS on the JVM.
* `test-conformance-clj-async` - Run ADL LRS Conformance Test Suite on an asynchronous Clojure LRS on the JVM.
* `test-conformance-cljs` - Run ADL LRS Conformance Test Suite on an (always asynchronous) ClojureScript LRS on node.
* `test-conformance` - Run all conformance tests.
* `test-all` - Run all lib and conformance tests.

## Bench Testing

Facilities to bench test any LRS with [DATASIM](https://github.com/yetanalytics/datasim) are available. For instance, to bench the in-memory LRS:

    $ make run-dev

And in another terminal:

    $ clojure -Abench -m com.yetanalytics.lrs.bench http://localhost:8080/xapi -s 1000 -b 100

This will bench the LRS with 1000 statements in POST batches of 100.

## License

Copyright © 2018-2021 Yet Analytics Inc.

Licensed under the Apache License, Version 2.0.
