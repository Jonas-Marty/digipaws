# Android build environment for digipaws. Everything Android-specific lives in
# here so the host stays clean. Versions follow the upstream build:
# AGP 8.7.2 needs JDK 17; compileSdk is 34.
FROM eclipse-temurin:17-jdk-noble

ARG CMDLINE_TOOLS_VERSION=13114758
ARG ANDROID_PLATFORM=34
ARG BUILD_TOOLS=34.0.0

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/home/dev/.gradle \
    DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl unzip git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL -o /tmp/cmdline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
    && mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip

# build-tools carries apksigner, zipalign and aapt2, which sign and inspect the APK.
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/build-tools/${BUILD_TOOLS}:${PATH}"

RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install \
        "platform-tools" \
        "platforms;android-${ANDROID_PLATFORM}" \
        "build-tools;${BUILD_TOOLS}" \
    > /dev/null

# Build as a user whose ids match the host account, so files written into the
# mounted workspace stay owned by the host user. Ubuntu already has uid 1000.
ARG USER_UID=1000
ARG USER_GID=1000
RUN if getent group "${USER_GID}" >/dev/null; then \
        groupmod -n dev "$(getent group "${USER_GID}" | cut -d: -f1)"; \
    else \
        groupadd -g "${USER_GID}" dev; \
    fi \
    && if getent passwd "${USER_UID}" >/dev/null; then \
        usermod -l dev -g "${USER_GID}" -d /home/dev -m -s /bin/bash "$(getent passwd "${USER_UID}" | cut -d: -f1)"; \
    else \
        useradd -m -u "${USER_UID}" -g "${USER_GID}" -s /bin/bash dev; \
    fi \
    && mkdir -p /home/dev/.gradle \
    && chown -R "${USER_UID}:${USER_GID}" /home/dev "${ANDROID_HOME}"

USER dev
WORKDIR /workspace
