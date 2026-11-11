FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY backup.py /app/backup.py
USER 65532:65532
CMD ["python3", "-u", "/app/backup.py"]
