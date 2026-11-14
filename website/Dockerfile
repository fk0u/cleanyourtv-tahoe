# Build from the repository root:  docker build -f website/Dockerfile -t tahoe-site .
# Run:                             docker run -d --name tahoe-site --restart unless-stopped -p 8081:80 tahoe-site
FROM alpine:3.20 AS build
WORKDIR /src
COPY website/ website/
COPY docs/ docs/
RUN sh website/build.sh

FROM nginx:1.27-alpine
COPY website/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/website/_site/ /usr/share/nginx/html/
