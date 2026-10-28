FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget su-exec \
    && addgroup -S app \
    && adduser -S -G app app \
    && install -d -m 0700 -o app -g app /data/qq-imports
WORKDIR /app
COPY --from=build /workspace/target/yingge-cloud-backend-*.jar app.jar
COPY docker-entrypoint.sh /usr/local/bin/yingge-backend-entrypoint
RUN chmod 0755 /usr/local/bin/yingge-backend-entrypoint
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/yingge-backend-entrypoint"]
