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

Use maven and the project's `pom.xml` to build + push the project:

* Edit the `pom.xml` to reflect the version you are releasing
* Run `$ mvn deploy` to build and deploy the artifact to Yaven (releases/snapshots). If you don't want to enter your username and password each time, add a ~/.m2/settings.xml like so:

``` xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                              https://maven.apache.org/xsd/settings-1.0.0.xsd">
                              <servers>
                                  <server>
                                      <id>yaven-snapshots</id>
                                      <username>milt</username>
                                      <password>****</password>
                                  </server>
                                  <server>
                                      <id>yaven-releases</id>
                                      <username>milt</username>
                                      <password>****</password>
                                  </server>
                                  <server>
                                      <id>clojars</id>
                                      <username>milt</username>
                                      <password>****</password>
                                  </server>
                              </servers>
</settings>
```

## License

Copyright © 2018 Yet Analytics Inc.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
