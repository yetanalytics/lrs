#!/bin/sh

node out/main.js >> lrs_cljs.log 2>&1 & echo $!
