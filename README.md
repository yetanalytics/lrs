# com.yetanalytics/lrs

[![CI](https://github.com/yetanalytics/lrs/actions/workflows/main.yml/badge.svg)](https://github.com/yetanalytics/lrs/actions/workflows/main.yml)

Yet's Core LRS Library. Includes a protocol `com.yetanalytics.lrs.protocol` and spec'd functions in `com.yetanalytics.lrs` to implement a learning record store.

## Usage

This project is set up to use the `clj`/`clojure` executable and `deps.edn`, with a handy `Makefile`.

To use the dev profile, which contains all dev/repl/test stuff, use the `:dev` alias: `-A:dev`.

`Makefile` targets of note:

* `clean` - Clean all build/ephemeral files. Note that this includes node_modules, package.json and package-lock.json.
* `node_modules` - As a convenience, have `cljs.main` figure out the node deps in `src/main/deps.cljs` and upstream, and pull them. You'll want this before using something like CIDER.
* `repl` - Run a Clojure repl with the `:dev` alias.
* `repl-cljs` - Run a Clojurescript node repl.
* `run-dev` - Start an in-memory implementation of the LRS in Clojure.
* `run-dev-cljs` - Start an in-memory implementation of the LRS in ClojureScript.
* `run-dev-cljs-simple` - Start an in-memory implementation of the LRS in ClojureScript with simple optimizations.
* `test-lib-clj` - Run the tests in Clojure.
* `test-lib-cljs` - Run the tests in ClojureScript.
* `test-lib` - Run the tests on both Clojure and ClojureScript.
* `test-conformance` - Download and run ADL's `lrs-conformance-test-suite` against the in-memory implementation (Clojure only)
* `test-all` - Run all lib and conformance tests.

## Deploying New Versions

No need, just refer to it with git deps

## Bench Testing

Facilities to bench test any LRS (with `DATASIM`) are available. For instance, to bench the in-memory LRS:

    $ make run-dev

And in another terminal:

    $ clojure -Abench -m com.yetanalytics.lrs.bench http://localhost:8080/xapi -s 1000 -b 100

This will bench the LRS with 1000 statements in POST batches of 100.

## License

Copyright © 2018-2021 Yet Analytics Inc.

Licensed under the Apache License, Version 2.0.
