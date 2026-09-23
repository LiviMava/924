FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./pom.xml
COPY src ./src
# Maven Central uses HTTPS. The image does not inherit host Maven settings.
RUN mvn -B -ntp package

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN groupadd --system --gid 10001 rag \
    && useradd --system --uid 10001 --gid rag --home-dir /app --shell /usr/sbin/nologin rag \
    && mkdir -p /app/data \
    && chown -R rag:rag /app
WORKDIR /app
COPY --from=build --chown=rag:rag /build/target/java-chatbot-1.0.0.jar /app/app.jar
USER 10001:10001
ENV SERVER_PORT=8090 \
    RAG_DATA_DIR=/app/data
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
