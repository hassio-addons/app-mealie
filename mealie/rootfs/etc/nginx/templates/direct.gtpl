server {
    {{ if not .ssl }}
    listen {{ .port }} default_server;
    {{ else }}
    listen {{ .port }} default_server ssl;
    http2 on;
    {{ end }}

    include /etc/nginx/includes/server_params.conf;
    include /etc/nginx/includes/proxy_params.conf;

    proxy_redirect off;

    {{ if .ssl }}
    include /etc/nginx/includes/ssl_params.conf;

    ssl_certificate /ssl/{{ .certfile }};
    ssl_certificate_key /ssl/{{ .keyfile }};
    {{ end }}

    # Served from the root here, so none of the Ingress rewriting applies and
    # Mealie's own login screen is what guards it.
    location / {
        proxy_pass http://backend;
    }
}
