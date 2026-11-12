# Tahoe TV website

The site at **[tahoe.kousozo.tech](https://tahoe.kousozo.tech)**: what Tahoe TV is, features, screenshots, downloads,
how to get started, community and FAQ. Plain HTML/CSS/JS, no build tools, English + Bahasa Indonesia
(picked from the browser, `?lang=id`, or the EN/ID switch).

| File | What |
|---|---|
| `index.html` | The page (English text lives here) |
| `assets/i18n.js` | Other languages. Add one by copying a block and adding a button in `.lang` |
| `assets/style.css`, `assets/app.js` | Look, animations, slideshow, language switch, live stars/version/contributors from the GitHub API |
| `build.sh` | Assembles `_site/` with the screenshots, hero art and logo from `docs/` |
| `Dockerfile`, `nginx.conf` | Container image serving the site |
| [`../.github/workflows/website.yml`](../.github/workflows/website.yml) | Builds and deploys on every push to `main` that touches the site |

## Preview locally

```bash
sh website/build.sh && python3 -m http.server -d website/_site 8080
```

Then open http://localhost:8080 (`?lang=id` for Indonesian).

## Deploy to your own server

Pick one.

### A. nginx on the server + GitHub Actions (rsync)

1. DNS: an `A` record `tahoe.kousozo.tech` → the server's IP.
2. On the server:
   ```bash
   sudo apt install -y nginx rsync certbot python3-certbot-nginx
   sudo mkdir -p /var/www/tahoe.kousozo.tech && sudo chown deploy: /var/www/tahoe.kousozo.tech
   ```
   nginx site (`/etc/nginx/sites-available/tahoe.kousozo.tech`, then link it into `sites-enabled`):
   ```nginx
   server {
       listen 80;
       server_name tahoe.kousozo.tech;
       root /var/www/tahoe.kousozo.tech;
       index index.html;
       add_header X-Content-Type-Options nosniff always;
       add_header Referrer-Policy strict-origin-when-cross-origin always;
       add_header X-Frame-Options DENY always;
       location /assets/ { expires 7d; }
       location / { try_files $uri $uri/ /index.html; }
   }
   ```
   ```bash
   sudo nginx -t && sudo systemctl reload nginx
   sudo certbot --nginx -d tahoe.kousozo.tech      # HTTPS
   ```
3. Create an SSH key just for deploys, put the public key in `~deploy/.ssh/authorized_keys`, and add the repository
   secrets `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY` and `DEPLOY_KNOWN_HOSTS` (the output of `ssh-keyscan <host>`, so the
   workflow only talks to your server); optionally `DEPLOY_PATH`, `DEPLOY_PORT`.
4. Run the **Website** workflow (Actions → Website → Run workflow). After that it deploys on every change.

### B. Docker

```bash
docker build -f website/Dockerfile -t tahoe-site .
docker run -d --name tahoe-site --restart unless-stopped -p 8081:80 tahoe-site
```

Put a reverse proxy with TLS in front of port 8081 (Caddy: `tahoe.kousozo.tech { reverse_proxy localhost:8081 }`).

---

# Situs web Tahoe TV (Bahasa Indonesia)

Situs di **[tahoe.kousozo.tech](https://tahoe.kousozo.tech/?lang=id)**: profil Tahoe TV, fitur, screenshot, unduhan,
cara pakai, komunitas, dan FAQ. HTML/CSS/JS biasa tanpa build tool, dalam bahasa Inggris + Indonesia.

- **Coba lokal:** `sh website/build.sh && python3 -m http.server -d website/_site 8080`, lalu buka http://localhost:8080/?lang=id.
- **Deploy (A):** arahkan DNS `tahoe.kousozo.tech` ke server, pasang blok nginx di atas + `certbot`, buat kunci SSH khusus deploy,
  lalu isi secret `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY` (opsional `DEPLOY_PATH`, `DEPLOY_PORT`) di repo. Workflow **Website** akan
  meng-upload situs setiap ada perubahan di `main`.
- **Deploy (B):** `docker build -f website/Dockerfile -t tahoe-site .` lalu `docker run -d -p 8081:80 tahoe-site` di belakang reverse proxy ber-TLS.
- **Tambah bahasa:** salin satu blok di `assets/i18n.js`, terjemahkan, dan tambahkan tombolnya di `.lang` pada `index.html`.
