# Krestianstvo | Electric - Clojure. Implementing a scalable Croquet VM.

## Work In Progress

This repository contains the implementation of the [Croquet VM](https://en.wikipedia.org/wiki/Croquet_OS) (based on [Krestianstvo SDK 4](https://github.com/NikolaySuslov/krestianstvo) and [Virtual World Framework](https://github.com/virtual-world-framework/vwf)) in [Electric - Clojure programming language](https://github.com/hyperfiddle/electric).
* In **<200 LoC** all parts of the classic **Croquet VM** are implemented, including **Reflector server**, **Virtual Time**, **Recursive Future Messages** etc. VM is distributed in a single Electric application as a **DAG**.
* Krestianstvo in Electric makes Croquet VM being scalable. Meaning, that previous single Reflector server can be scaled up across networks organising the fleet of Reflectors.
* Internal dispatcher of the Croquet VM messages queue can be distributed across hardware threads safely.
* Krestianstvo applications can be potentially run not only in web-browsers, but also in any Clojure hosted environment, scaling horizontally & vertically.

> Demo prototype is running at: [https://e.krestianstvo.org](https://e.krestianstvo.org)

Learn more about [**Krestianstvo SDK 4**](https://github.com/NikolaySuslov/krestianstvo-playground) here: [https://play.krestianstvo.org](https://play.krestianstvo.org)


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