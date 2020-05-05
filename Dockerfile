FROM clojure:openjdk-11-tools-deps as build

COPY . /build/

WORKDIR /build
RUN clojure -A:depstar -m hf.depstar.uberjar target/dromon-standalone.jar

FROM openjdk:13.0.1-jdk-slim

COPY --from=build /build/target/dromon-standalone.jar /app/

WORKDIR /app

CMD ["/bin/bash", "-c", "java $JVM_OPTS -jar dromon-standalone.jar -m dromon.core"]
