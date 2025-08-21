# Change Log

## Unreleased

## [1.2.21] - 2024-09-24
- Update xapi-schema to 1.4.0 to support empty statement batch POSTs
- Update GitHub actions to resolve Node deprecation warnings

## [1.2.20] - 2024-07-25
- Fix error in `/xapi/statements` POST OpenAPI spec

## [1.2.19] - 2024-07-18
- Implement OpenAPI annotations

## [1.2.18] - 2024-05-20
- Update xapi-schema to 1.3.0
- Address Babel vulnerability

## [1.2.17] - 2024-02-16
- Changed Clojars deploy action

## [1.2.16] - 2023-11-22
- Support Attachment and Document Scanning

## [1.2.15] - 2023-02-22
- CVE-2023-24998: Update commons-fileupload to 1.5
- Update package-lock for qs vulnerability

## [1.2.14] - 2022-11-16
- Exclude msgpack from dependencies to clear CVE-2022-41719

## [1.2.13] - 2022-10-24
- Update GitHub CI and CD to remove deprecation warnings

<!-- TODO: Fill in intervening tags -->

## [1.1.3] - 2021-11-10
- Fixed bug preventing attachments with duplicate SHAs
- Standardized signed statement comparison with extant statement comparison
- Allow environment configuration of dev in-memory LRS bind host and port

## [1.1.2] - 2021-10-06
- Updated `ws` and `ansi-regex` for minor security vulnerabilitites

## [1.1.0] - 2021-09-22
- Added `dissoc-statement-properties` and `statements-immut-equal?` in the `com.yetanalytics.lrs.xapi.statements` namespace, for dissoc-ing immutable Statement properties and comprehensive Statement equality checking, respectively.
- Deprecated `statements-equal?` in the aforementioned namespace.
- Updated the in-memory implementation to utilize these new functions.

## [1.0.1] - 2021-09-17
- Updated the Pedestal dependency to version 0.5.9, to address a [security vulnerability](https://github.com/pedestal/pedestal/issues/672).

## [1.0.0] - 2021-09-16
- Released `com.yetanalytics.lrs` as Open Source Software.
