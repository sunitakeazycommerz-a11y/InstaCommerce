FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

WORKDIR /app
COPY services/ai-inference-service/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY services/ai-inference-service/app ./app
COPY ml ./ml
RUN addgroup --system app && adduser --system --ingroup app app

USER app
ENV SERVER_PORT=8101
EXPOSE 8101
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD python -c "import os,urllib.request; urllib.request.urlopen(f'http://localhost:{os.environ.get(\"SERVER_PORT\",\"8101\")}/health')"

CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${SERVER_PORT}"]
