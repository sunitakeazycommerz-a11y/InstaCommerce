FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o app .

FROM alpine:3.20
RUN addgroup -g 1001 app && adduser -u 1001 -G app -D app
WORKDIR /app
COPY --from=build /src/app .
USER app
ENV PORT=8104
EXPOSE 8125
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD wget -qO- "http://localhost:${PORT}/health" || exit 1
ENTRYPOINT ["./app"]
