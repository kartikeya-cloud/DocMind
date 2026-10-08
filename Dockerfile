FROM node:22-alpine AS web
WORKDIR /web
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build
FROM maven:3.9-eclipse-temurin-17 AS server
WORKDIR /build
COPY backend/pom.xml ./pom.xml
RUN mvn -B dependency:go-offline
COPY backend/src ./src
COPY --from=web /web/dist ./src/main/resources/static
RUN mvn -B package
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S docmind && adduser -S docmind -G docmind
WORKDIR /app
COPY --from=server /build/target/docmind-1.0.0.jar app.jar
RUN mkdir /app/data && chown -R docmind:docmind /app
USER docmind
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]
