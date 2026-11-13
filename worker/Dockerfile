FROM python:3.12-slim
# ffmpeg: media-import clip cutting.
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY pyproject.toml ./
COPY src ./src
# pyproject pins the Sudachi full dictionary. SUDACHI_DICT=core swaps in the small core dictionary for smaller images.
ARG SUDACHI_DICT=full
RUN pip install --no-cache-dir . \
    && if [ "$SUDACHI_DICT" = "core" ]; then \
         pip uninstall -y sudachidict-full && pip install --no-cache-dir sudachidict-core==20250825; \
       fi
RUN mkdir -p /var/cache/kotobaya-jpdb && chown 65534 /var/cache/kotobaya-jpdb
USER 65534
EXPOSE 8081 8082
CMD ["python", "-m", "kotobaya_worker.runner"]
