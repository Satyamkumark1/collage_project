# Single-image build: React static assets get bundled into the Spring Boot jar so frontend and
# backend share one origin in production — sidesteps CORS and the cookie SameSite=Strict
# cross-origin breakage a split frontend/backend deploy would hit. See docs/DEPLOYMENT.md.

FROM node:22-alpine AS frontend-build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS backend-build
WORKDIR /app
COPY backend/.mvn .mvn
COPY backend/mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend-build /app/dist ./src/main/resources/static
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend-build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
