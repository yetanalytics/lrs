.phony: clean repl run-dev test-lib test-conformance test-all
clean:
	rm -rf target lrs-conformance-test-suite pom.xml.asc logs
repl:
	clj -A:dev -r
run-dev:
	clojure -A:dev -m mem-lrs.server
test-lib:
	clojure -A:dev -m com.yetanalytics.test-runner
test-conformance:
	clojure -A:dev -m com.yetanalytics.conformance-test
test-all: test-lib test-conformance clean
