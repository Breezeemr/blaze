# Breeze

This document contains some notes on breeze specific development and deployment notes. 
It's likely to be quickly evolving. 

## Development options

There are lots of ways to look at development options for blaze.

* tests vs repl driven development: tests run against in memory services and are isolated. RDD can connect to in memory or live
* whole system vs modules: whole system means working with everything at once. modules are isolated pieces of the system like the rest-api


### Whole System

These steps assume you have setup your database (see below)

- start a REPL with clj alias dev. e.g clj -A:dev.
- go into blaze.dev you will see two functions at the top `init` and `reset`
- evaluate (init) : to start the blaze server
- change some code.
- evaluate (reset) : to upload the changes to the blaze server

* NOTE it might be necessary to run with the clj alias "dev"
* NOTE it should also be possible to start the repl from within your editor. e.g for emacs `cider-jack-in` note that the .dir-locals file will effect that command.


#### configuration options

##### Database configuration

Blaze is an server api layer ontop of a datomic database. So you have options for development

* in memory db
* a live datomic database running in our kubernetes dev cluster.

###### In Memory

Is the default, nothing needs to be done


###### kubernetes development 

* HINT: if you run into problems, Consult the kuberentes live running service
first and in case of errors to see what connections its making. One easy way to
check this is to use the ./kubedecode script at the base of breeze_ehr e.g 
`./kubedecode service-config develompent`.

* NOTE: telelpresense doesn't handle proxies correctly so you have to use the external
ip

1. set the environment variable `DATABASE_URI`  (defaults to in memory) to point at the development datomic connection 
which probably looks like this `datomic:sql://phi-fhir-test`.
2.  This will also require you have credentials installed such that maven can find them. 
That means having a [settings.xml](https://maven.apache.org/settings.html) file in "~./m2/settings.xml" It should probably look like this

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

3. Finally, your local running blaze needs to be able to talk to the datomic database in development. Which means 
   your running a proxy. Probably via this command: `telepresence --namespace development`


### One Module Development in REPL from Cursive/EMACS
- start a REPL with alias dev and class path of the module using Cursive
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

