.phony: clean repl repl-cljs run-dev run-dev-cljs test-lib test-lib-cljs test-lib-clj test-conformance test-conformance-clj-sync test-conformance-clj-async test-conformance-cljs test-all ci npm-audit

clean:
	rm -rf target pom.xml.asc logs out node_modules .cljs_node_repl package.json out_test

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

out_test/test.js: node_modules
	clojure -Mdev -m cljs.main -re node -d out_test -o "out_test/test.js" -t nodejs -O none -c com.yetanalytics.test-runner

test-lib-cljs: out_test/test.js
	node out_test/test.js

test-lib-clj:
	clojure -Mdev -m com.yetanalytics.test-runner

test-lib: test-lib-clj test-lib-cljs

test-conformance-clj-sync:
	clojure -Mdev -m com.yetanalytics.conformance-test clj-sync

test-conformance-clj-async:
	clojure -Mdev -m com.yetanalytics.conformance-test clj-async

test-conformance-cljs: out/main.js
	clojure -Mdev -m com.yetanalytics.conformance-test cljs

test-conformance: test-conformance-clj-sync test-conformance-clj-async test-conformance-cljs

test-all: test-lib test-conformance

npm-audit: node_modules
	npm audit

ci: npm-audit test-all
