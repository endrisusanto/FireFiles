FROM python:3.12-alpine
WORKDIR /app
COPY server.py .
COPY assets ./assets
EXPOSE 8080
ENV DATA_FILE=/data/devices.json
CMD ["python", "server.py"]
