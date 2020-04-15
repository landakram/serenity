FROM openjdk:8-jre-alpine
RUN mkdir -p /app
WORKDIR /app

COPY target/uberjar/serenity-0.1.0-SNAPSHOT-standalone.jar .

CMD java -jar serenity-0.1.0-SNAPSHOT-standalone.jar -p 8000
EXPOSE 8000
