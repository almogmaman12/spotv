# SpotTV recognition bridge

This optional backend runs the open-source ShazamIO client for SpotTV. ShazamIO is an unofficial,
reverse-engineered client for Shazam's recognition service. It is free to run as software, but the
upstream service is not an official public API and can change or block requests.

## Deploy on Render's free plan

1. Create a new Render Blueprint and connect this repository.
2. Render detects `recognition-server/render.yaml`.
3. Deploy the `spottv-recognition` web service.
4. Copy its HTTPS URL. The health endpoint should return `{"status":"ok","provider":"ShazamIO"}`.
5. Configure that URL in SpotTV's recognition settings when the Android client is connected.

The free web service may sleep when idle, so the first recognition after inactivity can be slow.
Free hosting limits and availability can change. You can also run the container on your own Debian
server without paying for a hosted service.

## API

- `GET /health`: health check.
- `POST /v1/recognize`: ShazamIO match from a short audio sample.
- `POST /v1/acoustid/recognize`: Chromaprint fingerprint plus AcoustID lookup. Requires `ACOUSTID_CLIENT_KEY`.

Samples are limited to 2 MiB and rate-limited per client IP. The server does not save samples to disk.
Audio is sent onward to Shazam's service by ShazamIO for matching. Do not send audio unless the TV
owner has explicitly enabled recognition.

The Android client should submit approximately 10 to 12 seconds of audio, not a continuous stream.
A no-match response is `{"matched":false,"provider":"shazamio"}`.
