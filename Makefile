.phony: clean repl -cljs run-dev run-dev-cljs run-dev-cljs-simple test-lib test-lib-cljs test-lib-clj test-conformance test-all
lrs-conformance-test-suite:
	git clone --depth 1 https://github.com/adlnet/lrs-conformance-test-suite
	cd lrs-conformance-test-suite; npm install
clean:
	rm -rf target lrs-conformance-test-suite pom.xml.asc logs out
repl:
	clj -A:dev -r
repl-cljs:
	clj -A:dev:cljs -m cljs.main -re node -r
run-dev:
	clojure -A:dev -m mem-lrs.server
run-dev-cljs:
	clojure -A:dev:cljs -m cljs.main -t nodejs -c mem-lrs.server
	node out/main.js
run-dev-cljs-simple:
	clojure -A:dev:cljs -m cljs.main -t nodejs -O simple -c mem-lrs.server
	node out/main.js
test-lib-cljs:
	clojure -A:dev:cljs:test-cljs -c com.yetanalytics.test-runner
	node out/test.js
test-lib-clj:
	clojure -A:dev -m com.yetanalytics.test-runner
test-lib: test-lib-clj test-lib-cljs
test-conformance:
	clojure -A:dev -m com.yetanalytics.conformance-test
test-all: test-lib test-conformance
