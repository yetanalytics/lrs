# Change Log

## Unreleased
- Updated `ws` and `ansi-regex` for minor security vulnerabilitites

## [1.1.0] - 2021-09-22
- Added `dissoc-statement-properties` and `statements-immut-equal?` in the `com.yetanalytics.lrs.xapi.statements` namespace, for dissoc-ing immutable Statement properties and comprehensive Statement equality checking, respectively.
- Deprecated `statements-equal?` in the aforementioned namespace.
- Updated the in-memory implementation to utilize these new functions.

## [1.0.1] - 2021-09-17
- Updated the Pedestal dependency to version 0.5.9, to address a [security vulnerability](https://github.com/pedestal/pedestal/issues/672).

## [1.0.0] - 2021-09-16
- Released `com.yetanalytics.lrs` as Open Source Software.
