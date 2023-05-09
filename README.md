# Krestianstvo | Electric Clojure. Implementing a scalable Croquet VM.

## Work In Progress

This repository contains the implementation of the [Croquet VM](https://en.wikipedia.org/wiki/Croquet_OS) (based on [Krestianstvo SDK 4](https://github.com/NikolaySuslov/krestianstvo) and [Virtual World Framework](https://github.com/virtual-world-framework/vwf)) in [Electric Clojure programming language](https://github.com/hyperfiddle/electric).

The big idea here is to use the Electric distributed runtime as a basis for a higher level scene graph sync abstraction for use in collaborative virtual worlds.

Here are the first steps video with a <200 LoC classic Croquet VM implementation from Krestianstvo SDK 4:

https://user-images.githubusercontent.com/124158/236840769-66ac1328-136d-4a66-b01c-456ea58b71c9.mp4

* In **<200 LoC** all parts of the classic **Croquet VM** are implemented, including **Reflector server**, **Virtual Time**, **Recursive Future Messages** etc. VM is distributed in a single Electric application as a **DAG**.
* Krestianstvo in Electric makes Croquet VM being scalable. Meaning, that previous single Reflector server can be scaled up across networks organising the fleet of Reflectors.
* Internal dispatcher of the Croquet VM messages queue can be distributed across hardware threads safely.
* Krestianstvo applications can be potentially run not only in web-browsers, but also in any Clojure hosted environment, scaling horizontally & vertically.

> Demo prototype is running at: [https://e.krestianstvo.org](https://e.krestianstvo.org)

Learn more about [**Krestianstvo SDK 4**](https://github.com/NikolaySuslov/krestianstvo-playground) here: [https://play.krestianstvo.org](https://play.krestianstvo.org)

## Background

**Croquet** is a software development kit (SDK) for use in developing collaborative virtual world applications. The Croquet software architecture is known for its radical synchronization system with the notion of virtual time.

* [What is Croquet Anyways](https://blog.codefrau.net/2021/08/what-is-croquet-anyways.html)
* https://en.wikipedia.org/wiki/Croquet_Project
* https://en.wikipedia.org/wiki/Croquet_OS
* https://croquet.io/croquet-os/

The video of David P. Reed at OOPSLA '05, talking about core concepts of the Croquet architecture.  
[Designing croquet's TeaTime: a real-time, temporal environment for active object cooperation](https://dl.acm.org/doi/10.1145/1094855.1094861) 

Krestianstvo SDK 4 is the Open Source implementation of the Croquet application architecture in Functional Reactive Paradigm.

* https://github.com/NikolaySuslov/krestianstvo
* https://www.krestianstvo.org/
* https://docs.krestianstvo.org/en/introduction/
* https://www.krestianstvo.org/docs/about/publications/

Electric Clojure is a new web development paradigm that uses a compiler to build frontend/backend network sync directly into the programming language itself.

* [Electric Clojure](https://github.com/hyperfiddle/electric)

The Virtual World Framework (VWF) is a means to connect robust 3D, immersive, entities with other entities, virtual worlds, content and users via web browsers. 
* https://github.com/virtual-world-framework/vwf
* https://en.wikipedia.org/wiki/Virtual_world_framework

## Development

```
$ clj -A:dev -X user/main

Starting Electric compiler and server...
shadow-cljs - server version: 2.20.1 running at http://localhost:9630
shadow-cljs - nREPL server started on port 9001
[:app] Configuring build.
[:app] Compiling ...
[:app] Build completed. (224 files, 0 compiled, 0 warnings, 1.93s)

👉 App server available at http://0.0.0.0:8080
```

## Deployment

ClojureScript optimized build, Dockerfile, Uberjar, Github actions CD to fly.io

```
HYPERFIDDLE_ELECTRIC_APP_VERSION=`git describe --tags --long --always --dirty`
clojure -X:build build-client          # optimized release build
clojure -X:build uberjar :jar-name "app.jar" :version '"'$HYPERFIDDLE_ELECTRIC_APP_VERSION'"'
java -DHYPERFIDDLE_ELECTRIC_SERVER_VERSION=$HYPERFIDDLE_ELECTRIC_APP_VERSION -jar app.jar
```

```
docker build --progress=plain --build-arg VERSION="$HYPERFIDDLE_ELECTRIC_APP_VERSION" -t electric-starter-app .
docker run --rm -p 7070:8080 electric-starter-app
```

```
# flyctl launch ... ? create fly app, generate fly.toml, see dashboard
# https://fly.io/apps/electric-starter-app

NO_COLOR=1 flyctl deploy --build-arg VERSION="$HYPERFIDDLE_ELECTRIC_APP_VERSION"
# https://electric-starter-app.fly.dev/
```

- `NO_COLOR=1` disables docker-cli fancy shell GUI, so that we see the full log (not paginated) in case of exception
- `--build-only` tests the build on fly.io without deploying


## Contributing

All code is published under the MIT license
