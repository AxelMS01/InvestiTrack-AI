FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew
COPY src src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system identity && useradd --system --gid identity identity
COPY --from=build /workspace/build/libs/*-SNAPSHOT.jar app.jar
USER identity
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/app.jar"]
