FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
COPY scripts ./scripts
COPY models ./models
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-joblib python3-sklearn tesseract-ocr \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/fakejobpostsystem-0.0.1-SNAPSHOT.jar app.jar
COPY --from=build /app/scripts ./scripts
COPY --from=build /app/models ./models

ENV APP_PYTHON_COMMAND=python3
ENV APP_TESSERACT_COMMAND=tesseract
ENV APP_UPLOAD_DIR=/tmp/uploads

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
