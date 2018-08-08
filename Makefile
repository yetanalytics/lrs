.phony: clean repl -cljs run-dev test-lib test-lib-cljs test-lib-clj test-conformance test-all
clean:
	rm -rf target lrs-conformance-test-suite pom.xml.asc logs out
repl:
	clj -A:dev -r
repl-cljs:
	clj -A:dev -m cljs.main -re node -r
run-dev:
	clojure -A:dev -m mem-lrs.server
test-lib-cljs:
	clojure -A:dev -m cljs.main -t nodejs -o out/test.js -c com.yetanalytics.test-runner
	node out/test.js
test-lib-clj:
	clojure -A:dev -m com.yetanalytics.test-runner
test-lib: test-lib-clj test-lib-cljs
test-conformance:
	clojure -A:dev -m com.yetanalytics.conformance-test
test-all: test-lib test-conformance clean
