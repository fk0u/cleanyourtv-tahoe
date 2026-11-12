# Build from the repository root:  docker build -f website/Dockerfile -t tahoe-site .
# Run:                             docker run -d --name tahoe-site --restart unless-stopped -p 8081:80 tahoe-site
FROM nginx:1.27-alpine
COPY website/nginx.conf /etc/nginx/conf.d/default.conf
COPY website/index.html website/robots.txt website/sitemap.xml /usr/share/nginx/html/
COPY website/assets/ /usr/share/nginx/html/assets/
COPY docs/screenshots/v2 /usr/share/nginx/html/assets/shots/v2
COPY docs/screenshots/mobile-v4 /usr/share/nginx/html/assets/shots/mobile-v4
COPY docs/readme/hero.svg docs/brand/logo-mark.svg docs/brand/app-icon-1024.png /usr/share/nginx/html/assets/
