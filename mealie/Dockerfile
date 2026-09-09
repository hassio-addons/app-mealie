ARG BUILD_FROM=ghcr.io/hassio-addons/debian-base:9.4.0
ARG MEALIE_VERSION="3.24.0"

# hadolint ignore=DL3006
FROM ghcr.io/astral-sh/uv:0.12.12 AS uv

# Mealie's frontend is a Nuxt application that is generated up front and then
# packaged into the Python distribution, so it has to be built before the
# backend can be installed.
#
# What comes out is JavaScript and CSS, which is the same whatever it was built
# on, so this stage is pinned to the machine doing the building. Left to follow
# the target, an aarch64 image would run the whole Node toolchain under
# emulation for no benefit.
# hadolint ignore=DL3029
FROM --platform=${BUILDPLATFORM} node:24 AS frontend
ARG MEALIE_VERSION
ARG PNPM_VERSION="11.26.0"

ENV \
    NUXT_TELEMETRY_DISABLED=1

WORKDIR /usr/src/mealie

# Mealie aims at the site root, which is the one thing that stops it working
# under Home Assistant Ingress. The patches point it at SUB_PATH instead, a
# value NGINX writes into the page per request. The first adds a plugin that
# redirects fetch and XHR, which covers the API; the second fixes the few URLs
# the browser resolves itself, where no interceptor can reach.
COPY patches /patches

# hadolint ignore=DL3003,DL3016
RUN \
    curl -J -L -o /tmp/mealie.tar.gz \
        "https://github.com/mealie-recipes/mealie/archive/v${MEALIE_VERSION}.tar.gz" \
    && tar zxf /tmp/mealie.tar.gz --strip 1 -C /usr/src/mealie \
    \
    && patch -p1 < /patches/ingress-api-base.patch \
    && patch -p1 < /patches/ingress-absolute-urls.patch \
    \
    && npm install --global "pnpm@${PNPM_VERSION}" \
    \
    && cd /usr/src/mealie/frontend \
    && CI=true pnpm clean-install \
    && pnpm generate

# The backend is installed into a virtual environment of its own, which is the
# only thing that makes it into the app image. Nothing that was needed to
# compile it comes along.
# hadolint ignore=DL3006
FROM ${BUILD_FROM} AS builder

# Set shell
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

ENV \
    UV_LINK_MODE=copy \
    UV_NO_CACHE=1 \
    UV_PYTHON_INSTALL_DIR=/usr/local/share/uv/python \
    VIRTUAL_ENV=/opt/mealie

COPY --from=uv /uv /usr/local/bin/uv

# Only the parts of the source tree the Python distribution is built from, with
# the generated frontend dropped in where the build backend expects it.
COPY --from=frontend /usr/src/mealie/pyproject.toml /usr/src/mealie/uv.lock /usr/src/mealie/
COPY --from=frontend /usr/src/mealie/mealie /usr/src/mealie/mealie
COPY --from=frontend /usr/src/mealie/frontend/dist /usr/src/mealie/mealie/frontend

# Dependencies are read from the lock file rather than resolved. Upstream sets
# a rolling "exclude-newer" window in their pyproject that refuses anything
# released in the last few days, their own pinned versions included, so a
# resolve here would fail whenever they had just bumped something.
#
# Their "dev" group is a default group, which is how a plain export ends up
# dragging in mypy, pytest and the documentation toolchain as well.
#
# The ingredient parser tags words with an NLTK model, which is fetched from
# the nltk data repository directly rather than through nltk itself. Going
# through nltk pulls in numpy, which crashes on hardware without AVX.
#
# The version in the source tree is the literal string "develop"; upstream
# writes the release tag over it when they package, and so does this. Without
# it the about page and the Home Assistant integration report "develop".
ARG MEALIE_VERSION
ARG PYTHON_VERSION="3.12"
# hadolint ignore=DL3003,DL3008
RUN \
    apt-get update \
    \
    && apt-get install -y --no-install-recommends \
        build-essential=12.12 \
        libldap2-dev=2.6.10+dfsg-1 \
        libsasl2-dev=2.1.28+dfsg1-9 \
        libssl-dev=3.5.7-1~deb13u2 \
        unzip=6.0-29+deb13u1 \
    \
    && uv python install "${PYTHON_VERSION}" \
    && uv venv --seed --python "${PYTHON_VERSION}" "${VIRTUAL_ENV}" \
    \
    && cd /usr/src/mealie \
    && echo "__version__ = \"v${MEALIE_VERSION}\"" > mealie/__init__.py \
    && uv export \
        --frozen \
        --no-editable \
        --no-emit-project \
        --no-default-groups \
        --extra pgsql \
        --format requirements-txt \
        --output-file /tmp/requirements.txt \
    && uv pip install --requirements /tmp/requirements.txt \
    && uv pip install --no-deps /usr/src/mealie \
    \
    && mkdir -p /usr/share/nltk_data/taggers \
    && curl -J -L -o /tmp/tagger.zip \
        "https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/taggers/averaged_perceptron_tagger_eng.zip" \
    && unzip -q /tmp/tagger.zip -d /usr/share/nltk_data/taggers/ \
    \
    && find /usr/local/share/uv/python "${VIRTUAL_ENV}" \
        \( \
            \( -type d -a \( -name test -o -name tests -o -name idlelib \
                -o -name tkinter -o -name turtledemo -o -name ensurepip \
                -o -name __pycache__ \) \) \
            -o \( -type f -a \( -name '*.a' -o -name '*.pyc' -o -name '*.pyo' \) \) \
        \) \
        -exec rm -rf '{}' +

# hadolint ignore=DL3006
FROM ${BUILD_FROM}

# Set shell
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

ENV \
    NLTK_DATA=/usr/share/nltk_data \
    PATH="/opt/mealie/bin:${PATH}" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    VIRTUAL_ENV=/opt/mealie

# ffmpeg is what yt-dlp hands the video off to when a recipe is imported from
# one, and libldap is needed by python-ldap at runtime. NGINX faces Ingress and
# the network; Mealie itself only ever listens on loopback.
# hadolint ignore=DL3008
RUN \
    apt-get update \
    \
    && apt-get install -y --no-install-recommends \
        ffmpeg=7:7.1.5-0+deb13u1 \
        libldap-common=2.6.10+dfsg-1 \
        libpq5=17.11-0+deb13u1 \
        nginx=1.26.3-3+deb13u7 \
    \
    && rm -f -r /etc/nginx \
    \
    && apt-get clean \
    && rm -f -r \
        /tmp/* \
        /var/{cache,log}/* \
        /var/lib/apt/lists/*

COPY --from=builder /usr/local/share/uv/python /usr/local/share/uv/python
COPY --from=builder /opt/mealie /opt/mealie
COPY --from=builder /usr/share/nltk_data /usr/share/nltk_data

# Copy root filesystem
COPY rootfs /

# Health check
HEALTHCHECK \
    CMD curl --silent --fail http://127.0.0.1:49000/api/app/about || exit 1

# Build arguments
ARG BUILD_ARCH
ARG BUILD_DATE
ARG BUILD_DESCRIPTION
ARG BUILD_NAME
ARG BUILD_REF
ARG BUILD_REPOSITORY
ARG BUILD_VERSION

# Labels
LABEL \
    io.hass.name="${BUILD_NAME}" \
    io.hass.description="${BUILD_DESCRIPTION}" \
    io.hass.arch="${BUILD_ARCH}" \
    io.hass.type="addon" \
    io.hass.version=${BUILD_VERSION} \
    maintainer="Franck Nijhof <opensource@frenck.dev>" \
    org.opencontainers.image.title="${BUILD_NAME}" \
    org.opencontainers.image.description="${BUILD_DESCRIPTION}" \
    org.opencontainers.image.vendor="Home Assistant Community Apps" \
    org.opencontainers.image.authors="Franck Nijhof <opensource@frenck.dev>" \
    org.opencontainers.image.licenses="MIT" \
    org.opencontainers.image.url="https://frenck.dev/home-assistant-apps" \
    org.opencontainers.image.source="https://github.com/${BUILD_REPOSITORY}" \
    org.opencontainers.image.documentation="https://github.com/${BUILD_REPOSITORY}/blob/main/README.md" \
    org.opencontainers.image.created=${BUILD_DATE} \
    org.opencontainers.image.revision=${BUILD_REF} \
    org.opencontainers.image.version=${BUILD_VERSION}
