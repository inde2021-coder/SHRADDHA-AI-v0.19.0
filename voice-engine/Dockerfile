FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HOME=/tmp XDG_CACHE_HOME=/tmp/cache TMPDIR=/tmp
RUN apt-get update && apt-get install -y --no-install-recommends espeak-ng libespeak-ng1 && rm -rf /var/lib/apt/lists/*
WORKDIR /srv/shraddha-voice
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py .
RUN mkdir -p /models && addgroup --system voice && adduser --system --ingroup voice --home /tmp voice && chown -R voice:voice /srv/shraddha-voice
USER voice
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
