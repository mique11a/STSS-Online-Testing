FROM docker.m.daocloud.io/library/golang:1.26-alpine AS builder

WORKDIR /app

COPY go.mod go.sum ./
RUN go mod download

COPY cmd cmd
COPY configs configs
COPY pkg pkg

RUN CGO_ENABLED=0 GOOS=linux go build -o /out/logger-server ./cmd/server

FROM docker.m.daocloud.io/library/alpine:3.22

RUN apk add --no-cache ca-certificates && mkdir -p /log/ot

WORKDIR /app

COPY --from=builder /out/logger-server /app/logger-server

EXPOSE 50051

ENTRYPOINT ["/app/logger-server"]
