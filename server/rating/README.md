# LxChat rating submission API

This dependency-free Python service implements only LxChat's public rating submission endpoint:

```text
POST /api/rating
OPTIONS /api/rating
```

It stores accepted submissions in SQLite. It contains no read, listing, aggregation, dashboard, or administration endpoint. Every GET request returns `405 Method Not Allowed`.

## Installation

```sh
sudo useradd --system --home /nonexistent --shell /usr/sbin/nologin lxchat-rating
sudo install -d -o lxchat-rating -g lxchat-rating -m 0750 /var/lib/lxchat-rating
sudo install -d -o root -g root -m 0755 /opt/lxchat-rating
sudo install -o root -g root -m 0755 lxchat-rating-api.py /opt/lxchat-rating/
sudo install -o root -g root -m 0644 lxchat-rating.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now lxchat-rating
```

Copy `nginx-public.location` into the public TLS virtual host, validate the Nginx configuration, then reload Nginx.

## Configuration

| Variable | Default |
| --- | --- |
| `LXCHAT_RATING_DB` | `/var/lib/lxchat-rating/ratings.db` |
| `LXCHAT_RATING_HOST` | `127.0.0.1` |
| `LXCHAT_RATING_PORT` | `8091` |

No database, submitted record, host identity, domain, certificate, token, or credential is included.
