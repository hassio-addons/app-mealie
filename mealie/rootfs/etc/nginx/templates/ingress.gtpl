server {
    listen {{ .interface }}:{{ .port }} default_server;

    include /etc/nginx/includes/server_params.conf;
    include /etc/nginx/includes/proxy_params.conf;

    # A full page load of a route that exists as a directory in the generated
    # frontend, such as /admin/site-settings, is answered by the backend with a
    # redirect that adds a trailing slash. It builds that address out of the
    # request, so it comes back without the Ingress path on it.
    #
    # Only redirects pointing back at this app are rewritten, which leaves one
    # to an external identity provider alone. Left absolute, NGINX would then
    # rebuild the result around its own listening port rather than the one the
    # browser is talking to.
    absolute_redirect off;
    proxy_redirect http://$http_host/ $http_x_ingress_path/;
    proxy_redirect https://$http_host/ $http_x_ingress_path/;
    proxy_redirect / $http_x_ingress_path/;

    # Mealie builds its URLs from the site root, which is the wrong place when
    # Home Assistant hands it out from an Ingress path instead. That path is
    # only known per request, so it is written into the page on the way past.
    #
    # This is a handful of values in a 15KB document, not a rewrite of the
    # application: Nuxt reads "baseURL" back at runtime and resolves every
    # lazily loaded chunk against it, and the patched-in plugin does the same
    # with "SUB_PATH". The megabytes of JavaScript are never touched.
    sub_filter_once off;
    sub_filter '"/_nuxt/' '"$http_x_ingress_path/_nuxt/';
    sub_filter 'href="/favicon.ico"' 'href="$http_x_ingress_path/favicon.ico"';
    sub_filter 'href="/icons/' 'href="$http_x_ingress_path/icons/';
    sub_filter 'href="/manifest.webmanifest"' 'href="$http_x_ingress_path/manifest.webmanifest"';
    sub_filter 'SUB_PATH:""' 'SUB_PATH:"$http_x_ingress_path"';
    sub_filter 'app:{baseURL:"/"' 'app:{baseURL:"$http_x_ingress_path/"';

    location / {
        allow   172.30.32.2;
        deny    all;

        proxy_pass http://backend;
    }
}
