.phony: clean repl repl-cljs run-dev run-dev-cljs run-dev-cljs-simple test-lib test-lib-cljs test-lib-clj test-conformance test-all ci
lrs-conformance-test-suite:
	git clone --depth 1 https://github.com/adlnet/lrs-conformance-test-suite
	cd lrs-conformance-test-suite; rm package-lock.json; npm install
node_modules:
	npm install
clean:
	rm -rf target lrs-conformance-test-suite pom.xml.asc logs out node_modules
repl:
	clj -A:dev -r
repl-cljs: node_modules
	clj -A:dev -m cljs.main -re node -co "{:npm-deps,true}" -r
run-dev:
	clojure -A:dev -m mem-lrs.server
run-dev-cljs: node_modules
	clojure -A:dev -m cljs.main -t nodejs -co "{:npm-deps,true}" -c mem-lrs.server
	node out/main.js
run-dev-cljs-simple: node_modules
	clojure -A:dev -m cljs.main -t nodejs -O simple -co "{:npm-deps,true}" -c mem-lrs.server
	node out/main.js
test-lib-cljs: node_modules
	clojure -A:dev:test-cljs -co "{:npm-deps,true}" -c com.yetanalytics.test-runner
	node out/test.js
test-lib-clj:
	clojure -A:dev -m com.yetanalytics.test-runner
test-lib: test-lib-clj test-lib-cljs
test-conformance: lrs-conformance-test-suite
	# We need to set this to prevent an error on codebuild
	LC_ALL=en_US.utf-8 clojure -A:dev -m com.yetanalytics.conformance-test
test-all: test-lib test-conformance

ci: test-all
