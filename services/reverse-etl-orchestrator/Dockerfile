FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY go.sum* ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o app ./cmd/server

FROM alpine:3.24
RUN addgroup -g 1001 app && adduser -u 1001 -G app -D app
WORKDIR /app
COPY --from=build /src/app .
USER app
ENV API_ADDR=":8080"
ENV HEALTH_ADDR=":8081"
ENV METRICS_ADDR=":9090"
EXPOSE 8080 8081 9090
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD wget -qO- "http://localhost:8081/health" || exit 1
ENTRYPOINT ["./app"]

