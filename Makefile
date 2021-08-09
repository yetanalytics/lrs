.phony: clean repl repl-cljs run-dev run-dev-cljs test-lib test-lib-cljs test-lib-clj test-conformance test-all ci

clean:
	rm -rf target pom.xml.asc logs out node_modules .cljs_node_repl package.json package-lock.json

node_modules:
	clojure -Mdev -m cljs.main --install-deps

repl:
	clj -M:dev:test -r

repl-cljs: node_modules
	clj -Mdev -m cljs.main -re node -t nodejs -O none -c mem-lrs.server -r

run-dev:
	clojure -M:dev -m mem-lrs.server

# cljs build out path
out/main.js: node_modules
	clojure -Mdev -m cljs.main -re node -d out -t nodejs -O none -v -c mem-lrs.server

run-dev-cljs: out/main.js
	node out/main.js

out/test.js: node_modules
	clojure -Mdev -m cljs.main -re node -d out -o "out/test.js" -t nodejs -O none -c com.yetanalytics.test-runner

test-lib-cljs: out/test.js
	node out/test.js

test-lib-clj:
	clojure -Mdev -m com.yetanalytics.test-runner

test-lib: test-lib-clj test-lib-cljs

test-conformance: out/main.js
	clojure -Mdev -m com.yetanalytics.conformance-test

test-all: test-lib test-conformance

ci: test-all
