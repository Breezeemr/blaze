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

* NOTE it might be necessary to run with the clj alias "dev"
* NOTE it should also be possible to start the repl from within your editor. e.g for emacs `cider-jack-in`

#### configuration options

##### pick database

* in memory: default, nothing needs to be done
* breeze kubernetes development datomic:  set the environment variable `DATABASE_URI`  (defaults to in memory)
to the breeze kubernetes development datomic database connection string. This will also require you have 
credentials installed such that maven can find them. That means having a [settings.xml](https://maven.apache.org/settings.html) 
file in "~./m2/settings.xml" It should probably look like this

```xml
<settings>
  <servers>
    <server>
      <id>my.datomic.com</id>
      <username>[FILL ME]</username>
      <password>[FILL ME]</password>
    </server>
  </servers>
</settings>
```

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


## Deployment

The Deployment process for this app is handled within the `ops` (where this project/directory should be stored).


## Project clojure formatting

see [here](https://github.com/life-research/blaze/issues/67)

