# Breeze

This document contains some notes on breeze specific development and deployment notes. 
It's likely to be quickly evolving. 

## Development

### Whole System in REPL
- start a REPL with alias test and class path at the root of blaze
- go into blaze.dev
- evaluate (init)
- change something
- evaluate (reset)

note that starting the repl via editor (e.g cider-jack-in) had issues.

### One Module in REPL from Cursive/EMACS
- start a REPL with alias test and class path of the module using Cursive
- evaluate stuff
- run tests with Cursive
- Run all Tests
- on cmdline: make test
- Run one Module Test
- on cmdline: make test-rest-api

### Github issue

see https://github.com/life-research/blaze-doc/issues/3 for likely updates.


