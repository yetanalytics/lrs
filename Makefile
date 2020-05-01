.phony: clean repl repl-cljs run-dev run-dev-cljs run-dev-cljs-simple test-lib test-lib-cljs test-lib-clj test-conformance test-all ci

lrs-conformance-test-suite:
	git clone --depth 1 https://github.com/adlnet/lrs-conformance-test-suite
	cd lrs-conformance-test-suite; rm package-lock.json; npm install

clean:
	rm -rf target pom.xml.asc logs out node_modules .cljs_node_repl package.json package-lock.json

node_modules:
	clojure -Adev -m cljs.main --install-deps

repl:
	clj -A:dev:test -r

repl-cljs:
	clj -Adev -m cljs.main -re node -t nodejs -O none -co "{:install-deps,true}" -c mem-lrs.server -r

run-dev:
	clojure -A:dev -m mem-lrs.server

run-dev-cljs:
	clojure -Adev -m cljs.main -re node -d out -t nodejs -O none -co "{:install-deps,true}" -v -c mem-lrs.server
	node out/main.js

run-dev-cljs-simple:
	clojure -Adev -m cljs.main -re node -d out -t nodejs -O simple -co "{:install-deps,true}" -v -c mem-lrs.server
	node out/main.js

test-lib-cljs:
	clojure -Adev -m cljs.main -re node -d out -o "out/test.js" -t nodejs -O none -co "{:install-deps,true}" -c com.yetanalytics.test-runner
	node out/test.js

test-lib-clj:
	clojure -A:dev -m com.yetanalytics.test-runner

test-lib: test-lib-clj test-lib-cljs

test-conformance: lrs-conformance-test-suite
	clojure -A:dev -m com.yetanalytics.conformance-test

test-all: test-lib test-conformance

ci: test-all
