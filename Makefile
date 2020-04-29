.phony: clean repl repl-cljs run-dev run-dev-cljs run-dev-cljs-simple test-lib test-lib-cljs test-lib-clj test-conformance test-all ci
lrs-conformance-test-suite:
	git clone --depth 1 https://github.com/adlnet/lrs-conformance-test-suite
	cd lrs-conformance-test-suite; rm package-lock.json; npm install

clean:
	rm -rf target lrs-conformance-test-suite pom.xml.asc logs out node_modules package.json package-lock.json .cljs_node_repl

repl:
	clj -A:dev:test -r
repl-cljs:
	clj -A:dev -m cljs.main -re node -co "{:install-deps,true}" -r
run-dev:
	clojure -A:dev -m mem-lrs.server
run-dev-cljs:
	clojure -Adev -m cljs.main --target nodejs -O none -co "{:browser-repl,false,:install-deps,true}" -c mem-lrs.server
	node out/main.js
run-dev-cljs-simple:
	clojure -Adev -m cljs.main --target nodejs -O simple -co "{:browser-repl,false,:install-deps,true}" -c mem-lrs.server
	node out/main.js
test-lib-cljs:
	clojure -Adev -m cljs.main --target nodejs -O simple -o out/test.js -co "{:browser-repl,false,:install-deps,true}" -c com.yetanalytics.test-runner
	node out/test.js
test-lib-clj:
	clojure -A:dev -m com.yetanalytics.test-runner
test-lib: test-lib-clj test-lib-cljs
test-conformance: lrs-conformance-test-suite
	clojure -A:dev -m com.yetanalytics.conformance-test
test-all: test-lib test-conformance

ci: test-all
