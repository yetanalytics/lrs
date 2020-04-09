# lrs

Yet's Core LRS Library. Includes a protocol `com.yetanalytics.lrs.protocol` and spec'd functions in `com.yetanalytics.lrs` to implement a learning record store.

## Usage

This project is set up to use the `clj` executable and `deps.edn`, with a handy `Makefile`.

To use the dev profile, which contains all dev/repl/test stuff, use the `:dev` alias: `-A:dev`.

`Makefile` targets:

* `make clean` - Clean all build/ephemeral files
* `make repl` - Run a repl with the `:dev` alias
* `make run-dev` - Start an in-memory implementation of the LRS.
* `make test-lib` - Run the library's generative tests
* `make test-conformance` - Download and run ADL's `lrs-conformance-test-suite` against the in-memory implementation
* `make test-all` - Run lib and conformance tests, then clean

## Deploying New Versions

No need, just refer to it with git deps

## Bench Testing

Facilities to bench test any LRS (with `DATASIM`) are available. For instance, to bench the in-memory LRS:

    $ make run-dev

And in another terminal:

    $ clojure -Abench -m com.yetanalytics.lrs.bench http://localhost:8080/xapi -s 1000 -b 100

This will bench the LRS with 1000 statements in POST batches of 10.

## License

Copyright © 2018-2020 Yet Analytics Inc.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
